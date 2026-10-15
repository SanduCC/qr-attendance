package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Rules 3 and 4 are enforced by the database, not only by CheckInService: these bypass the
 * service and write straight through the repositories.
 */
class AttendanceRecordConstraintsTest extends IntegrationTestSupport {

    @Test
    void secondRecordForSameStudentInSameSessionViolatesUniqueConstraint() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        recordRepository.saveAndFlush(record(f, ana, "device-1"));

        assertThatThrownBy(() -> recordRepository.saveAndFlush(record(f, ana, "device-2")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_record_session_student");

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    @Test
    void secondRecordFromSameDeviceInSameSessionViolatesUniqueConstraint() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        Student bob = insertStudent("sub-bob", "Bogdan", "Ionescu");
        recordRepository.saveAndFlush(record(f, ana, "shared-phone"));

        assertThatThrownBy(() -> recordRepository.saveAndFlush(record(f, bob, "shared-phone")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_record_session_device");

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    @Test
    void sameStudentAndDeviceMayCheckInToDifferentSessions() {
        Fixture f = openFixture();
        UUID second = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");

        recordRepository.saveAndFlush(record(f, ana, "device-1"));
        recordRepository.saveAndFlush(new AttendanceRecord(second, ana.getId(), clock.instant(),
                hasher.hash("device", "device-1"), hasher.hash("ip", RAW_TEST_IP), null));

        assertThat(recordRepository.count()).isEqualTo(2);
    }

    @Test
    void studentsAreUniqueByProviderAndSubject() {
        insertStudent("sub-ana", "Ana", "Popescu");

        assertThatThrownBy(() -> insertStudent("sub-ana", "Someone", "Else"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_student_provider_subject");
    }

    private AttendanceRecord record(Fixture f, Student student, String rawDevice) {
        return new AttendanceRecord(f.sessionId(), student.getId(), clock.instant(),
                hasher.hash("device", rawDevice), hasher.hash("ip", RAW_TEST_IP), "JUnit");
    }
}
