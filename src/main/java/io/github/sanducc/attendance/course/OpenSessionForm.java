package io.github.sanducc.attendance.course;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * "Start attendance". Every field is optional so the one-click button can post nothing: a missing duration means the
 * configured default, a missing expected headcount means "same as this course's last session" unless
 * {@link #isHeadcountExplicit()} says the teacher deliberately left it empty in the options.
 */
public class OpenSessionForm {

    @Min(value = 1, message = "At least 1 minute")
    private Integer durationMinutes;

    @Min(value = 1, message = "Leave empty or enter at least 1")
    @Max(value = 5000, message = "That's a very large class")
    private Integer expectedHeadcount;

    /** Sent by the options form, where an empty expected-students field means "no expected headcount". */
    private boolean headcountExplicit;

    /** Rule 6 (#3): "Require location". Off unless ticked; the one-click start never turns it on. */
    private boolean requireLocation;

    /** Geofence radius in metres; null = the configured default. Bounds are checked by the controller. */
    private Integer geofenceRadiusM;

    /** The classroom: this device's position, filled in by start-options.js when "Require location" is ticked. */
    @DecimalMin(value = "-90", message = "Invalid location")
    @DecimalMax(value = "90", message = "Invalid location")
    private Double latitude;

    @DecimalMin(value = "-180", message = "Invalid location")
    @DecimalMax(value = "180", message = "Invalid location")
    private Double longitude;

    public OpenSessionForm() {
    }

    public OpenSessionForm(Integer durationMinutes, Integer expectedHeadcount) {
        this.durationMinutes = durationMinutes;
        this.expectedHeadcount = expectedHeadcount;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public Integer getExpectedHeadcount() {
        return expectedHeadcount;
    }

    public void setExpectedHeadcount(Integer expectedHeadcount) {
        this.expectedHeadcount = expectedHeadcount;
    }

    public boolean isHeadcountExplicit() {
        return headcountExplicit;
    }

    public void setHeadcountExplicit(boolean headcountExplicit) {
        this.headcountExplicit = headcountExplicit;
    }

    public boolean isRequireLocation() {
        return requireLocation;
    }

    public void setRequireLocation(boolean requireLocation) {
        this.requireLocation = requireLocation;
    }

    public Integer getGeofenceRadiusM() {
        return geofenceRadiusM;
    }

    public void setGeofenceRadiusM(Integer geofenceRadiusM) {
        this.geofenceRadiusM = geofenceRadiusM;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }
}
