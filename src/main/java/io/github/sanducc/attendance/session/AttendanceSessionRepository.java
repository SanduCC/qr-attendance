package io.github.sanducc.attendance.session;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, UUID> {

    List<AttendanceSession> findByCourseIdOrderByOpenedAtDesc(Long courseId);

    Optional<AttendanceSession> findFirstByCourseIdOrderByOpenedAtDesc(Long courseId);

    /** The most recent session of each given course (more than one per course only if opened at the same instant). */
    @Query("""
            select s from AttendanceSession s
            where s.courseId in :courseIds
              and s.openedAt = (select max(s2.openedAt) from AttendanceSession s2 where s2.courseId = s.courseId)
            """)
    List<AttendanceSession> findLatestByCourseIds(@Param("courseIds") Collection<Long> courseIds);

    @Modifying
    @Query("""
            update AttendanceSession s set s.status = io.github.sanducc.attendance.session.SessionStatus.CLOSED
            where s.status = io.github.sanducc.attendance.session.SessionStatus.OPEN and s.closesAt <= :now
            """)
    int closeExpired(@Param("now") Instant now);

    /** Attendance records go with their session via ON DELETE CASCADE. */
    @Modifying
    @Query("delete from AttendanceSession s where s.openedAt < :cutoff")
    int deleteOpenedBefore(@Param("cutoff") Instant cutoff);
}
