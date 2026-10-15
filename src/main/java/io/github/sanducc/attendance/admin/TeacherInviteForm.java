package io.github.sanducc.attendance.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class TeacherInviteForm {

    @NotBlank(message = "Please enter the teacher's Google account email")
    @Email(message = "That doesn't look like an email address")
    @Size(max = 320)
    private String email;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
