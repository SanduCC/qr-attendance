package io.github.sanducc.attendance.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CourseForm {

    @NotBlank(message = "Please enter a course name")
    @Size(max = 200, message = "At most 200 characters")
    private String name;

    /** Optional (#30); blank is stored as null. */
    @Size(max = 50, message = "At most 50 characters")
    private String code;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
