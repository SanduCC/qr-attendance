package io.github.sanducc.attendance.checkin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per check-in. UNIQUE(session_id, student_id) and UNIQUE(session_id, device_id_hash)
 * in the schema are the real enforcement of "once per account" and "once per device".
 */
@Entity
@Table(name = "attendance_record")
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    @Column(name = "device_id_hash", nullable = false)
    private String deviceIdHash;

    @Column(name = "ip_hash", nullable = false)
    private String ipHash;

    @Column(name = "user_agent")
    private String userAgent;

    protected AttendanceRecord() {
    }

    public AttendanceRecord(UUID sessionId, Long studentId, Instant checkedInAt, String deviceIdHash, String ipHash,
                            String userAgent) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.checkedInAt = checkedInAt;
        this.deviceIdHash = deviceIdHash;
        this.ipHash = ipHash;
        this.userAgent = userAgent;
    }

    public Long getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public Long getStudentId() {
        return studentId;
    }

    public Instant getCheckedInAt() {
        return checkedInAt;
    }

    public String getDeviceIdHash() {
        return deviceIdHash;
    }

    public String getIpHash() {
        return ipHash;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
