package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NameFormTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ana", "Ștefan", "Țărănescu", "Ioana-Maria", "O'Neil", "O’Neil", "Maria Ioana", "St. John",
            "Gheorghiță", "Mădălina", "Îngrid", "Âna", "José", "Zoë", "Łukasz", "Müller", "Дмитрий",
            "  Ana  ", "Ana  Maria",
            // "Ș" written as S + combining comma below (decomposed form from some keyboards)
            "Ștefan",
    })
    void acceptsRealNames(String name) {
        assertThat(violations(new NameForm(name, name))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ana3", "R2D2", "4na", "Ana😀", "😀", "Ana!", "<script>", "Ana@Pop", "Ana_Pop", "=SUM(A1)",
            "-Ana", "'Ana", ".Ana", "Ana\nPop", "Ana;Pop",
    })
    void rejectsNamesWithDigitsSymbolsOrEmoji(String name) {
        assertThat(violatedFields(new NameForm(name, "Popescu"))).containsExactly("firstName");
        assertThat(violatedFields(new NameForm("Ana", name))).containsExactly("lastName");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t"})
    void rejectsBlankNames(String name) {
        assertThat(violatedFields(new NameForm(name, name))).containsExactlyInAnyOrder("firstName", "lastName");
    }

    @Test
    void rejectsNamesLongerThan100Characters() {
        assertThat(violations(new NameForm("A".repeat(100), "B".repeat(100)))).isEmpty();

        assertThat(violatedFields(new NameForm("A".repeat(101), "Popescu"))).containsExactly("firstName");
        assertThat(violatedFields(new NameForm("Ana", "B".repeat(101)))).containsExactly("lastName");
    }

    @Test
    void normalizedTrimsAndCollapsesWhitespace() {
        NameForm normalized = new NameForm("  Ioana   Maria ", "\tPopescu  Ionescu\n").normalized();

        assertThat(normalized.getFirstName()).isEqualTo("Ioana Maria");
        assertThat(normalized.getLastName()).isEqualTo("Popescu Ionescu");
    }

    @Test
    void normalizedKeepsDiacriticsHyphensAndApostrophes() {
        NameForm normalized = new NameForm("Ștefan-Ioan", "O'Neil-Țărănescu").normalized();

        assertThat(normalized.getFirstName()).isEqualTo("Ștefan-Ioan");
        assertThat(normalized.getLastName()).isEqualTo("O'Neil-Țărănescu");
    }

    @Test
    void normalizedHandlesNull() {
        NameForm normalized = new NameForm(null, null).normalized();

        assertThat(normalized.getFirstName()).isNull();
        assertThat(normalized.getLastName()).isNull();
    }

    @Test
    void normalizedReturnsACopy() {
        NameForm form = new NameForm(" Ana ", " Pop ");

        NameForm normalized = form.normalized();

        assertThat(normalized).isNotSameAs(form);
        assertThat(form.getFirstName()).isEqualTo(" Ana ");
    }

    private static Set<ConstraintViolation<NameForm>> violations(NameForm form) {
        return validator.validate(form);
    }

    private static Set<String> violatedFields(NameForm form) {
        return violations(form).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
