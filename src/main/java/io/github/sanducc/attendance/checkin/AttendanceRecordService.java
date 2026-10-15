package io.github.sanducc.attendance.checkin;

import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.session.SessionService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Teacher-facing access to check-ins; every call is scoped to a session the teacher owns. */
@Service
public class AttendanceRecordService {

    private final SessionService sessions;
    private final AttendanceRecordRepository records;

    public AttendanceRecordService(SessionService sessions, AttendanceRecordRepository records) {
        this.sessions = sessions;
        this.records = records;
    }

    @Transactional(readOnly = true)
    public List<RecordView> listRecords(long teacherId, UUID sessionId) {
        sessions.getOwnedSession(teacherId, sessionId);
        return records.findViewsBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public long countRecords(long teacherId, UUID sessionId) {
        sessions.getOwnedSession(teacherId, sessionId);
        return records.countBySessionId(sessionId);
    }

    @Transactional
    public void removeRecord(long teacherId, UUID sessionId, long recordId) {
        sessions.getOwnedSession(teacherId, sessionId);
        AttendanceRecord record = records.findByIdAndSessionId(recordId, sessionId)
                .orElseThrow(() -> new NotFoundException("Record not found"));
        records.delete(record);
    }
}
