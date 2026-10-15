package io.github.sanducc.attendance.checkin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * First-registration name confirmation: letters (incl. diacritics), spaces, hyphens, apostrophes, dots.
 * A mutable form-backing bean rather than a record so Thymeleaf's th:field can bind to it.
 */
public class NameForm {

    static final String NAME_PATTERN = "^\\s*[\\p{L}\\p{M}][\\p{L}\\p{M} .'’\\-]*\\s*$";
    private static final String NAME_MESSAGE = "Use letters, spaces, hyphens or apostrophes";

    @NotBlank(message = "Please enter your first name")
    @Size(max = 100)
    @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
    private String firstName;

    @NotBlank(message = "Please enter your last name")
    @Size(max = 100)
    @Pattern(regexp = NAME_PATTERN, message = NAME_MESSAGE)
    private String lastName;

    public NameForm() {
    }

    public NameForm(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public NameForm normalized() {
        return new NameForm(normalize(firstName), normalize(lastName));
    }

    static String normalize(String name) {
        return name == null ? null : name.strip().replaceAll("\\s+", " ");
    }
}
