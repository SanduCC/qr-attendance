package io.github.sanducc.attendance.checkin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findBySessionIdAndStudentId(UUID sessionId, Long studentId);

    boolean existsBySessionIdAndDeviceIdHash(UUID sessionId, String deviceIdHash);

    /** Counts every entry, real scans and manual ones alike. */
    long countBySessionId(UUID sessionId);

    /** Check-in counts (manual entries included) for several sessions in one query; sessions without any are absent. */
    @Query("""
            select new io.github.sanducc.attendance.checkin.SessionCount(r.sessionId, count(r))
            from AttendanceRecord r
            where r.sessionId in :sessionIds
            group by r.sessionId
            """)
    List<SessionCount> countBySessionIds(@Param("sessionIds") Collection<UUID> sessionIds);

    Optional<AttendanceRecord> findByIdAndSessionId(Long id, UUID sessionId);

    /** The manual entry without a student that already carries this name (case-insensitive), if any. */
    @Query("""
            select r from AttendanceRecord r
            where r.sessionId = :sessionId and r.studentId is null
              and lower(r.manualFirstName) = lower(:firstName) and lower(r.manualLastName) = lower(:lastName)
            """)
    Optional<AttendanceRecord> findManualByName(@Param("sessionId") UUID sessionId,
                                                @Param("firstName") String firstName,
                                                @Param("lastName") String lastName);

    /** Every entry of the session with its name: the student's, or the free-text name of a manual entry. */
    @Query("""
            select new io.github.sanducc.attendance.checkin.RecordView(
                r.id, r.studentId, coalesce(s.firstName, r.manualFirstName), coalesce(s.lastName, r.manualLastName),
                s.email, r.checkedInAt, r.manual)
            from AttendanceRecord r left join Student s on s.id = r.studentId
            where r.sessionId = :sessionId
            order by r.checkedInAt, r.id
            """)
    List<RecordView> findViewsBySessionId(@Param("sessionId") UUID sessionId);
}
