package io.github.sanducc.attendance.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create or edit a course. */
public class CourseForm {

    @NotBlank(message = "Please enter a course name")
    @Size(max = 200, message = "At most 200 characters")
    private String name;

    /** Optional (#30); blank is stored as null. */
    @Size(max = 50, message = "At most 50 characters")
    private String code;

    /**
     * Optional Google Workspace domain lock (#4, rule 7) as typed, e.g. "university.ro, student.university.ro";
     * parsed and validated by {@link WorkspaceDomains#parse}. Blank = any Google account.
     */
    @Size(max = WorkspaceDomains.MAX_STORED_LENGTH, message = "At most " + WorkspaceDomains.MAX_STORED_LENGTH
            + " characters")
    private String allowedDomains;

    public static CourseForm of(Course course) {
        CourseForm form = new CourseForm();
        form.setName(course.getName());
        form.setCode(course.getCode());
        form.setAllowedDomains(WorkspaceDomains.format(course.getAllowedDomains()));
        return form;
    }

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

    public String getAllowedDomains() {
        return allowedDomains;
    }

    public void setAllowedDomains(String allowedDomains) {
        this.allowedDomains = allowedDomains;
    }
}
