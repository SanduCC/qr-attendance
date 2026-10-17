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
 *
 * <p>A <em>manual</em> entry was added by a teacher for someone who couldn't scan: it has no device or IP hash (NULLs
 * never collide in a unique constraint), records the teacher, and either points at an existing student or, for
 * someone without an account, carries a free-text name instead. The schema (ck_record_source) guarantees that a
 * non-manual record always has its student, device hash and IP hash.
 */
@Entity
@Table(name = "attendance_record")
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** Null only for a manual entry of someone without an account. */
    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;

    /** Null only for manual entries. */
    @Column(name = "device_id_hash")
    private String deviceIdHash;

    /** Null only for manual entries. */
    @Column(name = "ip_hash")
    private String ipHash;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(nullable = false)
    private boolean manual;

    @Column(name = "added_by_teacher_id")
    private Long addedByTeacherId;

    @Column(name = "manual_first_name")
    private String manualFirstName;

    @Column(name = "manual_last_name")
    private String manualLastName;

    protected AttendanceRecord() {
    }

    /** A real QR check-in. */
    public AttendanceRecord(UUID sessionId, Long studentId, Instant checkedInAt, String deviceIdHash, String ipHash,
                            String userAgent) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.checkedInAt = checkedInAt;
        this.deviceIdHash = deviceIdHash;
        this.ipHash = ipHash;
        this.userAgent = userAgent;
    }

    /** A teacher's manual entry for a student who already has an account. */
    public static AttendanceRecord manualForStudent(UUID sessionId, long studentId, long teacherId, Instant at) {
        AttendanceRecord record = new AttendanceRecord();
        record.sessionId = sessionId;
        record.studentId = studentId;
        record.checkedInAt = at;
        record.manual = true;
        record.addedByTeacherId = teacherId;
        return record;
    }

    /** A teacher's manual entry for someone without an account: the name lives on the record. */
    public static AttendanceRecord manualByName(UUID sessionId, String firstName, String lastName, long teacherId,
                                                Instant at) {
        AttendanceRecord record = new AttendanceRecord();
        record.sessionId = sessionId;
        record.checkedInAt = at;
        record.manual = true;
        record.addedByTeacherId = teacherId;
        record.manualFirstName = firstName;
        record.manualLastName = lastName;
        return record;
    }

    /** Only for a manual entry without a student; a student's name is changed on the {@link Student}. */
    void renameManualEntry(String firstName, String lastName) {
        if (studentId != null) {
            throw new IllegalStateException("Record " + id + " belongs to a student; rename the student instead");
        }
        this.manualFirstName = firstName;
        this.manualLastName = lastName;
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

    public boolean isManual() {
        return manual;
    }

    public Long getAddedByTeacherId() {
        return addedByTeacherId;
    }

    public String getManualFirstName() {
        return manualFirstName;
    }

    public String getManualLastName() {
        return manualLastName;
    }
}
