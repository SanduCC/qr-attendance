package io.github.sanducc.attendance.checkin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    Optional<AttendanceRecord> findBySessionIdAndStudentId(UUID sessionId, Long studentId);

    boolean existsBySessionIdAndDeviceIdHash(UUID sessionId, String deviceIdHash);

    long countBySessionId(UUID sessionId);

    Optional<AttendanceRecord> findByIdAndSessionId(Long id, UUID sessionId);

    @Query("""
            select new io.github.sanducc.attendance.checkin.RecordView(
                r.id, s.firstName, s.lastName, s.email, r.checkedInAt)
            from AttendanceRecord r, Student s
            where s.id = r.studentId and r.sessionId = :sessionId
            order by r.checkedInAt
            """)
    List<RecordView> findViewsBySessionId(@Param("sessionId") UUID sessionId);
}
