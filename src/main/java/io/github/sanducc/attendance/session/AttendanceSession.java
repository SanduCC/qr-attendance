package io.github.sanducc.attendance.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attendance_session")
public class AttendanceSession {

    @Id
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closes_at", nullable = false)
    private Instant closesAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    @Column(name = "expected_headcount")
    private Integer expectedHeadcount;

    @Column(name = "geofence_lat")
    private Double geofenceLat;

    @Column(name = "geofence_lng")
    private Double geofenceLng;

    @Column(name = "geofence_radius_m")
    private Integer geofenceRadiusM;

    protected AttendanceSession() {
    }

    public AttendanceSession(Long courseId, Instant openedAt, Instant closesAt, Integer expectedHeadcount) {
        this.id = UUID.randomUUID();
        this.courseId = courseId;
        this.openedAt = openedAt;
        this.closesAt = closesAt;
        this.status = SessionStatus.OPEN;
        this.expectedHeadcount = expectedHeadcount;
    }

    /** Open means not closed by the teacher and not past its end time. */
    public boolean isOpenAt(Instant now) {
        return status == SessionStatus.OPEN && now.isBefore(closesAt);
    }

    /** Rule 6: the classroom circle students must be in, or null for no location check (the default). */
    void setGeofence(Geofence geofence) {
        this.geofenceLat = geofence == null ? null : geofence.latitude();
        this.geofenceLng = geofence == null ? null : geofence.longitude();
        this.geofenceRadiusM = geofence == null ? null : geofence.radiusMeters();
    }

    /** The location check of this session, or null when it has none (any column missing means off). */
    public Geofence getGeofence() {
        if (geofenceLat == null || geofenceLng == null || geofenceRadiusM == null) {
            return null;
        }
        return new Geofence(geofenceLat, geofenceLng, geofenceRadiusM);
    }

    public void close(Instant now) {
        status = SessionStatus.CLOSED;
        if (now.isBefore(closesAt)) {
            closesAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosesAt() {
        return closesAt;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public Integer getExpectedHeadcount() {
        return expectedHeadcount;
    }

    public Double getGeofenceLat() {
        return geofenceLat;
    }

    public Double getGeofenceLng() {
        return geofenceLng;
    }

    public Integer getGeofenceRadiusM() {
        return geofenceRadiusM;
    }
}
