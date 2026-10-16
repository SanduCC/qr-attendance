package io.github.sanducc.attendance.course;

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
}
