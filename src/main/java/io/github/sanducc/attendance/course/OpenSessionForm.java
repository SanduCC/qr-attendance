package io.github.sanducc.attendance.course;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class OpenSessionForm {

    @NotNull(message = "Please enter a duration")
    @Min(value = 1, message = "At least 1 minute")
    private Integer durationMinutes;

    @Min(value = 1, message = "Leave empty or enter at least 1")
    @Max(value = 5000, message = "That's a very large class")
    private Integer expectedHeadcount;

    public OpenSessionForm() {
    }

    public OpenSessionForm(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
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
}
