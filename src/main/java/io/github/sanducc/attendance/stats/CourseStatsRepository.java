package io.github.sanducc.attendance.stats;

import io.github.sanducc.attendance.checkin.AttendanceRecord;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The two aggregate queries behind the per-student stats (#8). Both take a course id that the caller has already
 * checked with {@code CourseService.getOwnedCourse}; neither looks at who owns the course.
 */
public interface CourseStatsRepository extends Repository<AttendanceRecord, Long> {

    /** Counts the course's sessions (every status) and those with at least one entry. */
    @Query("""
            select new io.github.sanducc.attendance.stats.SessionTotals(
                count(distinct x.id), count(distinct r.sessionId), min(x.openedAt), max(x.openedAt))
            from AttendanceSession x left join AttendanceRecord r on r.sessionId = x.id
            where x.courseId = :courseId
            """)
    SessionTotals sessionTotals(@Param("courseId") long courseId);

    /**
     * One row per person with at least one entry in the course: per student (scans and manual entries for them), and
     * per typed name for manual entries without an account. ck_record_person guarantees a student's records carry no
     * manual name, so the lower(manual name) group keys are null for them and only split the typed-name entries.
     * Unsorted: the caller sorts with the Romanian collator.
     */
    @Query("""
            select new io.github.sanducc.attendance.stats.AttendeeStats(
                s.id, coalesce(s.firstName, min(r.manualFirstName)), coalesce(s.lastName, min(r.manualLastName)),
                s.email, count(distinct r.sessionId), max(x.openedAt))
            from AttendanceRecord r
                join AttendanceSession x on x.id = r.sessionId
                left join Student s on s.id = r.studentId
            where x.courseId = :courseId
            group by s.id, s.firstName, s.lastName, s.email, lower(r.manualLastName), lower(r.manualFirstName)
            """)
    List<AttendeeStats> attendees(@Param("courseId") long courseId);
}
