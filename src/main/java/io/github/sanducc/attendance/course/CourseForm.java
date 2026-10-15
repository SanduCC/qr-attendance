package io.github.sanducc.attendance.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CourseForm {

    @NotBlank(message = "Please enter a course name")
    @Size(max = 200)
    private String name;

    @NotBlank(message = "Please enter a course code")
    @Size(max = 50)
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
