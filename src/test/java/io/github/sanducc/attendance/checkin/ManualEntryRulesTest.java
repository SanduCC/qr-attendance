package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.session.Geofence;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Rules 6 and 7 guard <em>scans</em> only. A teacher's manual entry (#10), for a picked course student or for
 * someone without an account, is the teacher vouching for presence: neither the domain lock nor the geofence applies.
 */
class ManualEntryRulesTest extends IntegrationTestSupport {

    private static final String TEACHER = "prof.rules@university.example.ro";

    /**
     * A course locked to university.example.ro, with an open session that requires location. {@code pastStudent},
     * if given, attended an earlier session (so the teacher can pick them for a manual entry).
     */
    private UUID lockedGeofencedSession(Student pastStudent) {
        Teacher teacher = createTeacher(TEACHER);
        Course course = courseService.createCourse(teacher.getId(), "Algoritmi", "CS101",
                List.of("university.example.ro"));
        if (pastStudent != null) {
            UUID lastWeek = sessionService.openSession(teacher.getId(), course.getId(), 15, null).getId();
            insertRecord(lastWeek, pastStudent);
        }
        return sessionService.openSession(teacher.getId(), course.getId(), 15, null,
                new Geofence(44.4386, 26.0494, 100)).getId();
    }

    private RequestPostProcessor teacher() {
        return teacherLogin(TEACHER);
    }

    @Test
    void manualEntriesAreNotBlockedByTheDomainLockOrTheGeofence() throws Exception {
        // A course student whose account has no Workspace hd (checked in last week, before the lock was set).
        Student gmail = insertStudent("sub-gmail", "Ana", "Popescu");
        UUID sessionId = lockedGeofencedSession(gmail);

        mvc.perform(post("/sessions/{id}/records", sessionId).with(csrf()).with(teacher())
                        .param("studentId", String.valueOf(gmail.getId())))
                .andExpect(status().isCreated());
        mvc.perform(post("/sessions/{id}/records", sessionId).with(csrf()).with(teacher())
                        .param("firstName", "Carmen").param("lastName", "Dobre"))
                .andExpect(status().isCreated());

        assertThat(recordRepository.countBySessionId(sessionId)).isEqualTo(2);
        assertThat(recordRepository.findAll()).filteredOn(r -> r.getSessionId().equals(sessionId))
                .allMatch(AttendanceRecord::isManual);
    }

    @Test
    void theSameAccountScanningIsStillHeldToBothRules() throws Exception {
        UUID sessionId = lockedGeofencedSession(null);
        Browser phone = newBrowser();
        scan(phone, sessionId, validToken(sessionId)).andExpect(status().isOk());

        // No hd claim: refused by the domain lock, whatever the location.
        postCheckInWithLocation(phone, sessionId, studentLogin("sub-bob", "Bogdan", "Ionescu"), "Bogdan",
                "Ionescu", "44.4386", "26.0494", "10")
                .andExpect(status().isForbidden());
        // The right hd but no location: refused by the geofence.
        postCheckIn(phone, sessionId, workspaceStudentLogin("sub-ana", "ana@university.example.ro",
                "university.example.ro"), "Ana", "Popescu")
                .andExpect(status().isBadRequest());

        assertThat(recordRepository.countBySessionId(sessionId)).isZero();
    }
}
