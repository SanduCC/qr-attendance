package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.common.DeviceIdFilter;
import io.github.sanducc.attendance.common.InAppBrowser;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The student flow end to end: scan → sign in → confirm name → check in → done, plus every way to cheat it. */
class CheckInFlowTest extends IntegrationTestSupport {

    private static final String HEX_64 = "[0-9a-f]{64}";

    private RequestPostProcessor ana() {
        return studentLogin("sub-ana", "Ana", "Popescu");
    }

    private RequestPostProcessor bob() {
        return studentLogin("sub-bob", "Bogdan", "Ionescu");
    }

    private String doneUrl(UUID sessionId) {
        return "/a/" + sessionId + "/done";
    }

    /** Scan with a fresh token, then check in with the given names. Returns nothing; asserts the redirect. */
    private void checkInSuccessfully(Browser browser, UUID sessionId, RequestPostProcessor login, String first,
                                     String last) throws Exception {
        scan(browser, sessionId, validToken(sessionId)).andExpect(status().is2xxSuccessful());
        postCheckIn(browser, sessionId, login, first, last)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(doneUrl(sessionId)))
                .andExpect(flash().attribute("justCheckedIn", true));
        // Consume the flash attribute so it can't leak into a later request on this session.
        done(browser, sessionId, login).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void happyPathRecordsExactlyOneHashedCheckIn() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        // 1. Anonymous scan: token validated before any sign-in; pending check-in stored in the HTTP session.
        scan(phone, f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(view().name("student/landing"))
                .andExpect(model().attribute("sessionId", f.sessionId()))
                .andExpect(model().attribute("courseName", "Algoritmi"))
                .andExpect(model().attribute("inAppBrowser", InAppBrowser.NONE))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString(DeviceIdFilter.COOKIE_NAME + "="), containsString("HttpOnly"),
                        containsString("Secure"), containsString("SameSite=Lax"))));
        assertThat(phone.deviceCookie()).isNotNull();
        PendingCheckIn pending = phone.pending();
        assertThat(pending).isNotNull();
        assertThat(pending.sessionId()).isEqualTo(f.sessionId());
        assertThat(pending.scannedAt()).isEqualTo(clock.instant());
        assertThat(pending.deviceIdHash()).matches(HEX_64);

        // 2. OAuth round trip takes a while: the QR token itself is long expired, the pending scan is not.
        clock.advance(Duration.ofSeconds(40));
        confirm(phone, f.sessionId(), ana())
                .andExpect(status().isOk())
                .andExpect(view().name("student/confirm"))
                .andExpect(model().attribute("registered", false))
                .andExpect(model().attribute("email", "sub-ana@student.example.ro"))
                .andExpect(model().attribute("nameForm", allOf(
                        hasProperty("firstName", is("Ana")), hasProperty("lastName", is("Popescu")))));

        // 3. Check in, correcting the last name on first registration.
        postCheckIn(phone, f.sessionId(), ana(), "  Ana ", "Popescu-Ionescu")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(doneUrl(f.sessionId())))
                .andExpect(flash().attribute("justCheckedIn", true));
        assertThat(phone.pending()).as("pending scan is consumed").isNull();

        // 4. Success page (06:00:45Z is 09:00 in Bucharest).
        done(phone, f.sessionId(), ana())
                .andExpect(status().isOk())
                .andExpect(view().name("student/done"))
                .andExpect(model().attribute("justCheckedIn", true))
                .andExpect(model().attribute("firstName", "Ana"))
                .andExpect(model().attribute("lastName", "Popescu-Ionescu"))
                .andExpect(model().attribute("courseName", "Algoritmi"))
                .andExpect(model().attribute("checkedInAt", "09:00"));

        // 5. Database: one student keyed on the OAuth subject, one record, only hashes stored.
        Student student = findStudent("sub-ana").orElseThrow();
        assertThat(student.getFirstName()).isEqualTo("Ana");
        assertThat(student.getLastName()).isEqualTo("Popescu-Ionescu");
        assertThat(student.getEmail()).isEqualTo("sub-ana@student.example.ro");

        List<AttendanceRecord> records = recordRepository.findAll();
        assertThat(records).hasSize(1);
        AttendanceRecord record = records.getFirst();
        assertThat(record.getSessionId()).isEqualTo(f.sessionId());
        assertThat(record.getStudentId()).isEqualTo(student.getId());
        assertThat(record.getCheckedInAt()).isEqualTo(clock.instant());

        String rawDevice = phone.rawDeviceId();
        assertThat(record.getDeviceIdHash()).matches(HEX_64)
                .isNotEqualTo(rawDevice)
                .doesNotContain(rawDevice)
                .isEqualTo(hasher.hash("device", rawDevice));
        assertThat(record.getIpHash()).matches(HEX_64)
                .isNotEqualTo(RAW_TEST_IP)
                .isEqualTo(hasher.hash("ip", RAW_TEST_IP));
        Integer rawValuesStored = jdbc.queryForObject(
                "select count(*) from attendance_record where ip_hash = ? or device_id_hash = ?",
                Integer.class, RAW_TEST_IP, rawDevice);
        assertThat(rawValuesStored).isZero();
    }

    @Test
    void scanningWhileAlreadySignedInGoesStraightToConfirm() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        phone.perform(get("/a/{id}", f.sessionId()).param("t", validToken(f.sessionId())).with(ana()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/a/" + f.sessionId() + "/confirm"));

        assertThat(phone.pending()).isNotNull();
    }

    // ------------------------------------------------------------------ rule 1: rotating token

    @Test
    void tokenTwoWindowsOldIsRejectedBeforeSignIn() throws Exception {
        Fixture f = openFixture();
        String token = validToken(f.sessionId());
        clock.advance(Duration.ofSeconds(30));
        Browser phone = newBrowser();

        scan(phone, f.sessionId(), token)
                .andExpect(status().isGone())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "This QR code has expired"));

        assertThat(phone.pending()).isNull();
    }

    @Test
    void photographedTokenIsUselessMinutesLater() throws Exception {
        Fixture f = openFixture();
        String token = validToken(f.sessionId());
        clock.advance(Duration.ofMinutes(2));

        scan(newBrowser(), f.sessionId(), token).andExpect(status().isGone());
    }

    @Test
    void previousWindowTokenIsAccepted() throws Exception {
        Fixture f = openFixture();
        String token = validToken(f.sessionId());
        clock.advance(Duration.ofSeconds(15));
        Browser phone = newBrowser();

        scan(phone, f.sessionId(), token)
                .andExpect(status().isOk())
                .andExpect(view().name("student/landing"));

        assertThat(phone.pending()).isNotNull();
    }

    @Test
    void missingGarbageOrOtherSessionTokensAreRejected() throws Exception {
        Fixture f = openFixture();
        UUID other = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();

        newBrowser().perform(get("/a/{id}", f.sessionId())).andExpect(status().isGone());
        scan(newBrowser(), f.sessionId(), "garbage").andExpect(status().isGone());
        scan(newBrowser(), f.sessionId(), validToken(other)).andExpect(status().isGone());
    }

    @Test
    void expiredTokenCannotBeCompletedByPostingDirectly() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        // No successful scan in this browser: posting the check-in form directly gets nowhere.
        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(status().isGone())
                .andExpect(view().name("student/message"));

        assertThat(recordRepository.count()).isZero();
        assertThat(studentRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ rule 2: grace period, bound to browser

    @Test
    void checkInAfterTheGracePeriodIsRejected() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));

        confirm(phone, f.sessionId(), ana()).andExpect(status().isGone());
        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(status().isGone())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Please scan again"));

        assertThat(recordRepository.count()).isZero();
        assertThat(studentRepository.count()).isZero();
    }

    @Test
    void checkInJustInsideTheGracePeriodSucceeds() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(3).minusSeconds(1));

        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    @Test
    void scanInOneBrowserCannotBeFinishedInAnother() throws Exception {
        Fixture f = openFixture();
        Browser phoneInClass = newBrowser();
        scan(phoneInClass, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        // A friend at home, with their own browser (own session, own device cookie).
        Browser friendAtHome = newBrowser();
        confirm(friendAtHome, f.sessionId(), bob()).andExpect(status().isGone());
        postCheckIn(friendAtHome, f.sessionId(), bob(), "Bogdan", "Ionescu")
                .andExpect(status().isGone())
                .andExpect(view().name("student/message"));

        assertThat(recordRepository.count()).isZero();
        assertThat(findStudent("sub-bob")).isEmpty();
    }

    @Test
    void pendingScanIsBoundToTheDeviceCookieNotJustTheSession() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        // Some other device obtains its own device cookie...
        Browser otherDevice = newBrowser();
        otherDevice.perform(get("/a/{id}", f.sessionId()));
        assertThat(otherDevice.deviceCookie()).isNotNull();
        assertThat(otherDevice.rawDeviceId()).isNotEqualTo(phone.rawDeviceId());

        // ...and replays the phone's HTTP session with it.
        Browser hijacked = phone.withDeviceCookieOf(otherDevice);
        postCheckIn(hijacked, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(status().isGone())
                .andExpect(view().name("student/message"));
        assertThat(recordRepository.count()).isZero();

        // Same session without any device cookie: a new device id is issued, still rejected.
        Browser noCookie = phone.withDeviceCookieOf(newBrowser());
        postCheckIn(noCookie, f.sessionId(), ana(), "Ana", "Popescu").andExpect(status().isGone());
        assertThat(recordRepository.count()).isZero();

        // The genuine phone can still finish.
        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ rule 3: one check-in per account

    @Test
    void secondCheckInShowsAlreadyCheckedInAndKeepsOneRecord() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        checkInSuccessfully(phone, f.sessionId(), ana(), "Ana", "Popescu");

        clock.advance(Duration.ofMinutes(10));
        phone.perform(get("/a/{id}", f.sessionId()).param("t", validToken(f.sessionId())).with(ana()))
                .andExpect(redirectedUrl("/a/" + f.sessionId() + "/confirm"));
        confirm(phone, f.sessionId(), ana()).andExpect(redirectedUrl(doneUrl(f.sessionId())));
        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(doneUrl(f.sessionId())))
                .andExpect(flash().attributeCount(0));

        done(phone, f.sessionId(), ana())
                .andExpect(status().isOk())
                .andExpect(view().name("student/done"))
                .andExpect(model().attribute("justCheckedIn", false))
                // The original check-in time, not the second attempt.
                .andExpect(model().attribute("checkedInAt", "09:00"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    @Test
    void sameAccountFromASecondPhoneIsStillOneRecord() throws Exception {
        Fixture f = openFixture();
        checkInSuccessfully(newBrowser(), f.sessionId(), ana(), "Ana", "Popescu");

        Browser secondPhone = newBrowser();
        scan(secondPhone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        postCheckIn(secondPhone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())))
                .andExpect(flash().attributeCount(0));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
        assertThat(studentRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ rule 4: one check-in per device

    @Test
    void secondAccountOnTheSamePhoneIsRejected() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        checkInSuccessfully(phone, f.sessionId(), ana(), "Ana", "Popescu");

        // Ana signs out and her friend signs in on the same phone: new HTTP session, same device cookie.
        Browser samePhone = phone.sameDeviceNewSession();
        scan(samePhone, f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(view().name("student/landing"));
        postCheckIn(samePhone, f.sessionId(), bob(), "Bogdan", "Ionescu")
                .andExpect(status().isConflict())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "This phone was already used"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
        assertThat(findStudent("sub-bob")).isEmpty();
    }

    @Test
    void theOtherStudentCanStillCheckInFromTheirOwnPhone() throws Exception {
        Fixture f = openFixture();
        Browser anasPhone = newBrowser();
        checkInSuccessfully(anasPhone, f.sessionId(), ana(), "Ana", "Popescu");
        Browser borrowed = anasPhone.sameDeviceNewSession();
        scan(borrowed, f.sessionId(), validToken(f.sessionId()));
        postCheckIn(borrowed, f.sessionId(), bob(), "Bogdan", "Ionescu").andExpect(status().isConflict());

        checkInSuccessfully(newBrowser(), f.sessionId(), bob(), "Bogdan", "Ionescu");

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(2);
    }

    @Test
    void samePhoneMayBeUsedAgainInALaterSession() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        checkInSuccessfully(phone, f.sessionId(), ana(), "Ana", "Popescu");

        UUID nextSession = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        Browser samePhone = phone.sameDeviceNewSession();
        checkInSuccessfully(samePhone, nextSession, bob(), "Bogdan", "Ionescu");

        assertThat(recordRepository.countBySessionId(nextSession)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ names

    @Test
    void namesAreLockedAfterFirstRegistration() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        checkInSuccessfully(phone, f.sessionId(), ana(), "Ana", "Popescu");

        clock.advance(Duration.ofDays(7));
        UUID nextWeek = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        // The identity provider now reports a different name; the stored one still wins.
        RequestPostProcessor renamed = studentLogin("sub-ana", "Anastasia", "Popa");
        Browser samePhone = phone.sameDeviceNewSession();
        scan(samePhone, nextWeek, validToken(nextWeek)).andExpect(status().isOk());
        confirm(samePhone, nextWeek, renamed)
                .andExpect(status().isOk())
                .andExpect(model().attribute("registered", true))
                .andExpect(model().attribute("nameForm", allOf(
                        hasProperty("firstName", is("Ana")), hasProperty("lastName", is("Popescu")))));

        // Submitted names (even invalid ones) are ignored once registered.
        postCheckIn(samePhone, nextWeek, renamed, "Hacker1", "Changed")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(doneUrl(nextWeek)));

        assertThat(studentRepository.count()).isEqualTo(1);
        Student student = findStudent("sub-ana").orElseThrow();
        assertThat(student.getFirstName()).isEqualTo("Ana");
        assertThat(student.getLastName()).isEqualTo("Popescu");
        assertThat(recordRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidNamesOnFirstRegistrationReRenderTheForm() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        postCheckIn(phone, f.sessionId(), ana(), "Ana3", " ")
                .andExpect(status().isBadRequest())
                .andExpect(view().name("student/confirm"))
                .andExpect(model().attribute("registered", false))
                .andExpect(model().attributeHasFieldErrors("nameForm", "firstName", "lastName"));

        assertThat(studentRepository.count()).isZero();
        assertThat(recordRepository.count()).isZero();
        assertThat(phone.pending()).as("the scan is still usable").isNotNull();

        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    @Test
    void emojiAndOverlongNamesAreRejected() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        postCheckIn(phone, f.sessionId(), ana(), "Ana😀", "P".repeat(101))
                .andExpect(status().isBadRequest())
                .andExpect(model().attributeHasFieldErrors("nameForm", "firstName", "lastName"));

        assertThat(studentRepository.count()).isZero();
    }

    @Test
    void romanianDiacriticsAreStoredIntact() throws Exception {
        Fixture f = openFixture();
        checkInSuccessfully(newBrowser(), f.sessionId(), studentLogin("sub-st", "Stefan", "Taranescu"),
                "Ștefan", "Țărănescu");

        Student student = findStudent("sub-st").orElseThrow();
        assertThat(student.getFirstName()).isEqualTo("Ștefan");
        assertThat(student.getLastName()).isEqualTo("Țărănescu");
    }

    // ------------------------------------------------------------------ closed sessions

    @Test
    void scanAfterTeacherClosedTheSessionIsRejected() throws Exception {
        Fixture f = openFixture();
        sessionService.close(f.teacherId(), f.sessionId());

        scan(newBrowser(), f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isGone())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Check-in is closed"));
    }

    @Test
    void scanAfterTheSessionExpiredIsRejected() throws Exception {
        Fixture f = openFixture();
        clock.advance(Duration.ofMinutes(15));

        scan(newBrowser(), f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isGone())
                .andExpect(model().attribute("title", "Check-in is closed"));
    }

    @Test
    void studentWhoScannedBeforeClosingMayFinishWithinTheGracePeriod() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(30));
        sessionService.close(f.teacherId(), f.sessionId());
        clock.advance(Duration.ofSeconds(30));

        postCheckIn(phone, f.sessionId(), ana(), "Ana", "Popescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));
        assertThat(recordRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ misc

    @Test
    void unknownSessionIsNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();

        scan(newBrowser(), unknown, "whatever")
                .andExpect(status().isNotFound())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Session not found"));
    }

    @Test
    void malformedSessionIdIsABadRequest() throws Exception {
        newBrowser().perform(get("/a/not-a-uuid").param("t", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void checkInWithoutCsrfTokenIsForbidden() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        phone.perform(post("/a/{id}/check-in", f.sessionId()).with(ana())
                        .param("firstName", "Ana").param("lastName", "Popescu"))
                .andExpect(status().isForbidden());

        assertThat(recordRepository.count()).isZero();
    }

    @Test
    void signedOutStudentsAreSentToLoginForEverythingButTheScan() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        phone.perform(get("/a/{id}/confirm", f.sessionId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        phone.perform(post("/a/{id}/check-in", f.sessionId()).with(csrf())
                        .param("firstName", "Ana").param("lastName", "Popescu"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));

        assertThat(recordRepository.count()).isZero();
    }

    @Test
    void donePageWithoutACheckInSaysSo() throws Exception {
        Fixture f = openFixture();

        done(newBrowser(), f.sessionId(), ana())
                .andExpect(status().isNotFound())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Not checked in yet"));
    }

    @Test
    void landingPageWarnsInsideInstagramOnAndroid() throws Exception {
        Fixture f = openFixture();
        String instagram = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A; wv) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Version/4.0 Chrome/127.0 Mobile Safari/537.36 Instagram 343.0.0.33.101 Android";
        String token = validToken(f.sessionId());

        newBrowser().perform(get("/a/{id}", f.sessionId()).param("t", token)
                        .header(HttpHeaders.USER_AGENT, instagram))
                .andExpect(status().isOk())
                .andExpect(view().name("student/landing"))
                .andExpect(model().attribute("inAppBrowser", InAppBrowser.ANDROID))
                .andExpect(model().attribute("currentUrl", "http://localhost/a/" + f.sessionId() + "?t=" + token));
    }
}
