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

    // ------------------------------------------------------------------ manual entries (#10, V4)

    @Test
    void manualEntriesWithNullDeviceHashesCoexistWithEachOtherAndWithRealScans() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        Student bob = insertStudent("sub-bob", "Bogdan", "Ionescu");

        // Postgres treats NULLs as distinct in a unique constraint: none of these collide on the device constraint,
        // and the two entries without a student don't collide on (session_id, student_id) either.
        recordRepository.saveAndFlush(AttendanceRecord.manualForStudent(f.sessionId(), ana.getId(), f.teacherId(),
                clock.instant()));
        recordRepository.saveAndFlush(AttendanceRecord.manualByName(f.sessionId(), "Carmen", "Dobre", f.teacherId(),
                clock.instant()));
        recordRepository.saveAndFlush(AttendanceRecord.manualByName(f.sessionId(), "Ion", "Dobre", f.teacherId(),
                clock.instant()));
        recordRepository.saveAndFlush(record(f, bob, "device-1"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(4);
        Integer nullDevices = jdbc.queryForObject(
                "select count(*) from attendance_record where device_id_hash is null", Integer.class);
        assertThat(nullDevices).isEqualTo(3);
        // The device constraint still applies to real scans.
        Student dan = insertStudent("sub-dan", "Dan", "Pop");
        assertThatThrownBy(() -> recordRepository.saveAndFlush(record(f, dan, "device-1")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_record_session_device");
    }

    @Test
    void aManualEntryAndARealScanOfTheSameStudentViolateTheStudentConstraint() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        recordRepository.saveAndFlush(AttendanceRecord.manualForStudent(f.sessionId(), ana.getId(), f.teacherId(),
                clock.instant()));

        assertThatThrownBy(() -> recordRepository.saveAndFlush(record(f, ana, "device-1")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_record_session_student");
        assertThatThrownBy(() -> recordRepository.saveAndFlush(AttendanceRecord.manualForStudent(f.sessionId(),
                ana.getId(), f.teacherId(), clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_record_session_student");
    }

    @Test
    void theSameFreeTextNameCantBeAddedTwiceToASession() {
        Fixture f = openFixture();
        UUID other = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        recordRepository.saveAndFlush(AttendanceRecord.manualByName(f.sessionId(), "Carmen", "Dobre", f.teacherId(),
                clock.instant()));

        assertThatThrownBy(() -> recordRepository.saveAndFlush(AttendanceRecord.manualByName(f.sessionId(),
                "CARMEN", "dobre", f.teacherId(), clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_record_session_manual_name");
        // Another session is fine.
        recordRepository.saveAndFlush(AttendanceRecord.manualByName(other, "Carmen", "Dobre", f.teacherId(),
                clock.instant()));
    }

    @Test
    void aRealScanMustHaveItsStudentDeviceAndIpHash() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        String insert = "insert into attendance_record (session_id, student_id, checked_in_at, device_id_hash, ip_hash,"
                + " manual) values (?, ?, now(), ?, ?, ?)";

        assertThatThrownBy(() -> jdbc.update(insert, f.sessionId(), ana.getId(), null, "ip", false))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_record_source");
        assertThatThrownBy(() -> jdbc.update(insert, f.sessionId(), ana.getId(), "device", null, false))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_record_source");
        // No student and not manual breaks both checks; which one Postgres names first doesn't matter here.
        assertThatThrownBy(() -> jdbc.update(insert, f.sessionId(), null, "device", "ip", false))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_record_");
        // A manual entry never carries device or IP data.
        assertThatThrownBy(() -> jdbc.update(insert, f.sessionId(), ana.getId(), "device", "ip", true))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_record_source");
        // A manual entry without a student needs a name.
        assertThatThrownBy(() -> jdbc.update(insert, f.sessionId(), null, null, null, true))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_record_person");

        assertThat(recordRepository.count()).isZero();
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
