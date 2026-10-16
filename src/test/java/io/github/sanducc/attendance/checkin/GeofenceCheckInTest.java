package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.session.Geofence;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Anti-cheat rule 6: the optional location check, end to end. */
class GeofenceCheckInTest extends IntegrationTestSupport {

    private static final double LAT = 44.4386;
    private static final double LNG = 26.0494;
    private static final double DEG_PER_M = 1 / (Math.toRadians(1) * Geofence.EARTH_RADIUS_M);

    private RequestPostProcessor ana() {
        return studentLogin("sub-ana", "Ana", "Popescu");
    }

    private RequestPostProcessor bob() {
        return studentLogin("sub-bob", "Bogdan", "Ionescu");
    }

    /** Teacher + course + an open session whose classroom is (LAT, LNG) with the given radius. */
    private UUID geofencedSession(int radiusM) {
        Teacher teacher = createTeacher("prof.geo@university.example.ro");
        Course course = courseService.createCourse(teacher.getId(), "Algoritmi", "CS101");
        return sessionService.openSession(teacher.getId(), course.getId(), 15, null,
                new Geofence(LAT, LNG, radiusM)).getId();
    }

    private static String latMetresNorth(double metres) {
        return String.format(Locale.ROOT, "%.7f", LAT + metres * DEG_PER_M);
    }

    private static String lng() {
        return String.valueOf(LNG);
    }

    private String doneUrl(UUID sessionId) {
        return "/a/" + sessionId + "/done";
    }

    // ------------------------------------------------------------------ inside / outside

    @Test
    void insideTheRadiusChecksInAndNoCoordinatesAreStored() throws Exception {
        UUID sessionId = geofencedSession(100);
        Browser phone = newBrowser();
        scan(phone, sessionId, validToken(sessionId))
                .andExpect(status().isOk())
                .andExpect(model().attribute("locationRequired", true))
                .andExpect(content().string(containsString("checks that you're in the room")));
        confirm(phone, sessionId, ana())
                .andExpect(status().isOk())
                .andExpect(model().attribute("locationRequired", true))
                .andExpect(content().string(allOf(containsString("/js/checkin-location.js"),
                        containsString("name=\"latitude\""), containsString("name=\"accuracy\""))));

        postCheckInWithLocation(phone, sessionId, ana(), "Ana", "Popescu", latMetresNorth(40), lng(), "25")
                .andExpect(redirectedUrl(doneUrl(sessionId)));

        assertThat(recordRepository.countBySessionId(sessionId)).isEqualTo(1);
        // GDPR: the position is compared and forgotten. No table a student row lands in has a place for it.
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name in ('attendance_record', 'student')", String.class);
        assertThat(columns).noneMatch(c -> c.contains("lat") || c.contains("lng") || c.contains("lon")
                || c.contains("geo") || c.contains("accuracy") || c.contains("location"));
    }

    @Test
    void outsideTheRadiusIsRejectedAndTheStudentMayRetryWithinTheGracePeriod() throws Exception {
        UUID sessionId = geofencedSession(100);
        Browser phone = newBrowser();
        scan(phone, sessionId, validToken(sessionId)).andExpect(status().isOk());

        // 2 km away (at home), even claiming a poor accuracy: the credit is capped at 100 m.
        postCheckInWithLocation(phone, sessionId, ana(), "Ana", "Popescu", latMetresNorth(2000), lng(), "1500")
                .andExpect(status().isForbidden())
                .andExpect(view().name("student/confirm"))
                .andExpect(model().attribute("locationError", containsString("about 2.0 km")))
                .andExpect(model().attribute("registered", false));
        assertThat(recordRepository.count()).isZero();
        assertThat(findStudent("sub-ana")).as("nothing written, not even the student").isEmpty();
        assertThat(phone.pending()).isNotNull();

        // 250 m away with ±20 m: still outside, distance rounded.
        postCheckInWithLocation(phone, sessionId, ana(), "Ana", "Popescu", latMetresNorth(248), lng(), "20")
                .andExpect(status().isForbidden())
                .andExpect(model().attribute("locationError", containsString("about 250 m")));

        // Back in the room a minute later: fine.
        clock.advance(Duration.ofMinutes(1));
        postCheckInWithLocation(phone, sessionId, ana(), "Ana", "Popescu", latMetresNorth(10), lng(), "15")
                .andExpect(redirectedUrl(doneUrl(sessionId)));
        assertThat(recordRepository.countBySessionId(sessionId)).isEqualTo(1);
    }

    @Test
    void reportedAccuracyIsCreditedUpToTheCap() throws Exception {
        UUID sessionId = geofencedSession(100);

        // 150 m away, ±60 m: 150 - 60 = 90 m <= 100 m.
        Browser ana = newBrowser();
        scan(ana, sessionId, validToken(sessionId));
        postCheckInWithLocation(ana, sessionId, ana(), "Ana", "Popescu", latMetresNorth(150), lng(), "60")
                .andExpect(redirectedUrl(doneUrl(sessionId)));

        // 230 m away, claiming ±5 km: credited only 100 m, so 130 m > 100 m.
        Browser bob = newBrowser();
        scan(bob, sessionId, validToken(sessionId));
        postCheckInWithLocation(bob, sessionId, bob(), "Bogdan", "Ionescu", latMetresNorth(230), lng(), "5000")
                .andExpect(status().isForbidden());

        assertThat(recordRepository.countBySessionId(sessionId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ missing location

    @Test
    void missingOrUnusableLocationIsRejectedWhenRequired() throws Exception {
        UUID sessionId = geofencedSession(100);
        Student registered = insertStudent("sub-ana", "Ana", "Popescu");
        Browser phone = newBrowser();
        scan(phone, sessionId, validToken(sessionId)).andExpect(status().isOk());

        // No JS / permission denied: nothing sent.
        postCheckIn(phone, sessionId, ana(), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(view().name("student/confirm"))
                .andExpect(model().attribute("locationError", containsString("Allow location access")))
                .andExpect(model().attribute("registered", true))
                .andExpect(content().string(containsString("Ana Popescu")));
        // Tampered values: treated as missing, never a 500.
        for (String[] bad : new String[][] {{"", ""}, {"abc", "def"}, {"NaN", "26.0"}, {"95", "26.0"},
                {"44.4", "200"}, {"44.4", null}}) {
            postCheckInWithLocation(phone, sessionId, ana(), "Ana", "Popescu", bad[0], bad[1], "10")
                    .andExpect(status().isBadRequest())
                    .andExpect(view().name("student/confirm"));
        }
        assertThat(recordRepository.count()).isZero();
        assertThat(findStudent("sub-ana").orElseThrow().getId()).isEqualTo(registered.getId());
    }

    // ------------------------------------------------------------------ off by default: unchanged

    @Test
    void withoutAGeofenceLocationIsNeitherAskedForNorChecked() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId()))
                .andExpect(model().attribute("locationRequired", false))
                .andExpect(content().string(not(containsString("checks that you're in the room"))));
        confirm(phone, f.sessionId(), ana())
                .andExpect(model().attribute("locationRequired", false))
                .andExpect(content().string(allOf(not(containsString("checkin-location.js")),
                        not(containsString("name=\"latitude\"")))));

        // Whatever a client sends is ignored.
        postCheckInWithLocation(phone, f.sessionId(), ana(), "Ana", "Popescu", "0", "0", "1")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));
        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the other rules still hold

    @Test
    void aGoodLocationDoesNotBypassTheDeviceRuleOrTheGracePeriod() throws Exception {
        UUID sessionId = geofencedSession(100);
        String inside = latMetresNorth(5);

        Browser phone = newBrowser();
        scan(phone, sessionId, validToken(sessionId));
        postCheckInWithLocation(phone, sessionId, ana(), "Ana", "Popescu", inside, lng(), "10")
                .andExpect(redirectedUrl(doneUrl(sessionId)));

        // Rule 4: a second account on the same phone, in the room, is still refused.
        Browser samePhone = phone.sameDeviceNewSession();
        scan(samePhone, sessionId, validToken(sessionId));
        postCheckInWithLocation(samePhone, sessionId, bob(), "Bogdan", "Ionescu", inside, lng(), "10")
                .andExpect(status().isConflict());

        // Rule 2: a scan older than the grace period, in the room, is still refused.
        Browser late = newBrowser();
        scan(late, sessionId, validToken(sessionId));
        clock.advance(Duration.ofMinutes(4));
        postCheckInWithLocation(late, sessionId, bob(), "Bogdan", "Ionescu", inside, lng(), "10")
                .andExpect(status().isGone());

        // Rule 2 again: no scan at all, in the room.
        postCheckInWithLocation(newBrowser(), sessionId, bob(), "Bogdan", "Ionescu", inside, lng(), "10")
                .andExpect(status().isGone());

        assertThat(recordRepository.countBySessionId(sessionId)).isEqualTo(1);
    }
}
