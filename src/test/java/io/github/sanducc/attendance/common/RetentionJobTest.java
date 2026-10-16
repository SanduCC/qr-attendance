package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.checkin.AttendanceRecord;
import io.github.sanducc.attendance.checkin.Student;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** GDPR retention: academic year ends 09-30, 30 grace days (application.yml defaults). */
class RetentionJobTest extends IntegrationTestSupport {

    @Autowired RetentionJob retentionJob;

    private UUID lastYearSession;
    private UUID thisYearSession;
    private Student onlyLastYear;
    private Student bothYears;
    private Student onlyThisYear;
    private Course course;

    @BeforeEach
    void seed() {
        // Academic year 2025/26: a session in September 2026.
        clock.set(Instant.parse("2026-09-10T07:00:00Z"));
        Teacher teacher = createTeacher("prof@university.example.ro");
        course = courseService.createCourse(teacher.getId(), "Algoritmi", "CS101");
        lastYearSession = sessionService.openSession(teacher.getId(), course.getId(), 15, null).getId();
        onlyLastYear = insertStudent("sub-old", "Ana", "Popescu");
        bothYears = insertStudent("sub-both", "Bogdan", "Ionescu");
        insertRecord(lastYearSession, onlyLastYear);
        insertRecord(lastYearSession, bothYears);

        // Academic year 2026/27: a session in October 2026.
        clock.set(Instant.parse("2026-10-05T07:00:00Z"));
        thisYearSession = sessionService.openSession(teacher.getId(), course.getId(), 15, null).getId();
        onlyThisYear = insertStudent("sub-new", "Carmen", "Dobre");
        insertRecord(thisYearSession, bothYears);
        insertRecord(thisYearSession, onlyThisYear);
    }

    @Test
    void duringTheGracePeriodNothingIsPurged() {
        clock.set(Instant.parse("2026-10-20T01:30:00Z"));

        retentionJob.purge();

        assertThat(sessionRepository.count()).isEqualTo(2);
        assertThat(recordRepository.count()).isEqualTo(4);
        assertThat(studentRepository.count()).isEqualTo(3);
    }

    @Test
    void afterTheGracePeriodLastYearsSessionsRecordsAndOrphanStudentsArePurged() {
        clock.set(Instant.parse("2026-11-15T01:30:00Z"));

        retentionJob.purge();

        assertThat(sessionRepository.existsById(lastYearSession)).isFalse();
        assertThat(sessionRepository.existsById(thisYearSession)).isTrue();

        assertThat(recordRepository.countBySessionId(lastYearSession)).isZero();
        assertThat(recordRepository.countBySessionId(thisYearSession)).isEqualTo(2);
        assertThat(recordRepository.count()).isEqualTo(2);

        assertThat(studentRepository.existsById(onlyLastYear.getId())).as("orphan student purged").isFalse();
        assertThat(studentRepository.existsById(bothYears.getId())).isTrue();
        assertThat(studentRepository.existsById(onlyThisYear.getId())).isTrue();

        // Teachers and courses are not personal attendance data and stay.
        assertThat(teacherRepository.count()).isEqualTo(1);
        assertThat(courseService.listCourses(course.getTeacherId())).hasSize(1);
    }

    @Test
    void manualEntriesArePurgedWithTheirSessionAndTheirStudents() {
        // Last year: a manual entry for someone without an account, and one for a student who had no other entry.
        clock.set(Instant.parse("2026-09-10T08:00:00Z"));
        Student onlyManual = insertStudent("sub-manual", "Dan", "Pop");
        recordRepository.saveAndFlush(AttendanceRecord.manualByName(lastYearSession, "Carmen", "Dobre",
                course.getTeacherId(), clock.instant()));
        recordRepository.saveAndFlush(AttendanceRecord.manualForStudent(lastYearSession, onlyManual.getId(),
                course.getTeacherId(), clock.instant()));
        // This year: a manual entry that must stay.
        AttendanceRecord kept = recordRepository.saveAndFlush(AttendanceRecord.manualByName(thisYearSession, "Ion",
                "Dobre", course.getTeacherId(), clock.instant()));
        clock.set(Instant.parse("2026-11-15T01:30:00Z"));

        RetentionJob.Result result = retentionJob.purge();

        assertThat(result.deletedStudents()).isEqualTo(2);
        assertThat(recordRepository.findAll()).extracting(AttendanceRecord::getSessionId).containsOnly(thisYearSession);
        assertThat(recordRepository.count()).isEqualTo(3);
        assertThat(recordRepository.existsById(kept.getId())).isTrue();
        Integer freeTextNames = jdbc.queryForObject(
                "select count(*) from attendance_record where manual_last_name is not null", Integer.class);
        assertThat(freeTextNames).as("only this year's free-text name is left").isEqualTo(1);
        assertThat(studentRepository.existsById(onlyManual.getId())).isFalse();
        assertThat(studentRepository.existsById(onlyLastYear.getId())).isFalse();
    }

    @Test
    void purgeIsIdempotent() {
        clock.set(Instant.parse("2026-11-15T01:30:00Z"));

        retentionJob.purge();
        retentionJob.purge();

        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(recordRepository.count()).isEqualTo(2);
        assertThat(studentRepository.count()).isEqualTo(2);
    }
}
