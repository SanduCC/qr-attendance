package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ana@example.ro|ana@example.ro",
            "  Ana.Popescu@Example.RO  |ana.popescu@example.ro",
            "a.n.a+class2@gmail.com|a.n.a+class2@gmail.com",
            "o'brien@uni.example.ac.uk|o'brien@uni.example.ac.uk",
            "x@xn--80ak6aa92e.com|x@xn--80ak6aa92e.com"})
    void normalizesByTrimmingAndLowercasingOnly(String raw, String normalized) {
        assertThat(EmailAddresses.normalize(raw)).contains(normalized);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ana", "ana@", "@example.ro", "ana@example", "ana@@example.ro",
            "ana@b@example.ro", "ana pop@example.ro", "ana@exa mple.ro", ".ana@example.ro", "ana.@example.ro",
            "an..a@example.ro", "ana@example..ro", "ana@-example.ro", "ana@example.ro-", "ană@example.ro",
            "ana@exämple.ro", "\"ana\"@example.ro", "ana@[127.0.0.1]", "ana@exa\nmple.ro", "ana@example.1"})
    void rejectsWhatIsNotAPlainAddress(String raw) {
        assertThat(EmailAddresses.normalize(raw)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(EmailAddresses.normalize(null)).isEmpty();
    }

    @Test
    void capsTheLength() {
        String local = "a".repeat(64);
        String domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".ro";
        assertThat(local + "@" + domain).hasSize(254);
        assertThat(EmailAddresses.normalize(local + "@" + domain)).isPresent();
        assertThat(EmailAddresses.normalize("x" + local + "@" + domain)).as("255 characters").isEmpty();
        assertThat(EmailAddresses.normalize("a".repeat(65) + "@example.ro")).as("65-character local part").isEmpty();
    }

    @Test
    void masksForLogs() {
        assertThat(EmailAddresses.mask("ana.popescu@example.ro")).isEqualTo("a***@example.ro");
        assertThat(EmailAddresses.mask("a@x.ro")).isEqualTo("a***@x.ro");
        assertThat(EmailAddresses.mask(null)).isEqualTo("(none)");
        assertThat(EmailAddresses.mask("no-at")).isEqualTo("***");
    }

    @Test
    void logIdIsAStablePepperedTagWithNoPartOfTheAddress() {
        PepperedHasher hasher = new PepperedHasher(TestProperties.defaults());
        String id = EmailAddresses.logId(hasher, "ana.popescu@example.ro");

        assertThat(id).matches("email#[0-9a-f]{" + PepperedHasher.LOG_ID_LENGTH + "}")
                .doesNotContain("ana").doesNotContain("example").doesNotContain("@");
        assertThat(EmailAddresses.logId(hasher, "ana.popescu@example.ro")).as("same address, same tag").isEqualTo(id);
        assertThat(EmailAddresses.logId(hasher, "ana.pop@example.ro")).isNotEqualTo(id);
        assertThat(EmailAddresses.logId(hasher, null)).isEqualTo("email#(none)");
    }

    @Test
    void logIdDependsOnThePepper() {
        PepperedHasher otherPepper = new PepperedHasher(TestProperties.with(TestProperties.QR_SECRET,
                "another-unit-test-pepper-0123456789abcdef", true));
        assertThat(EmailAddresses.logId(otherPepper, "ana@example.ro"))
                .isNotEqualTo(EmailAddresses.logId(new PepperedHasher(TestProperties.defaults()), "ana@example.ro"));
    }

    @Test
    void domainOfTheNormalizedAddress() {
        assertThat(EmailAddresses.domainOf("ana@student.university.ro")).isEqualTo("student.university.ro");
    }
}
