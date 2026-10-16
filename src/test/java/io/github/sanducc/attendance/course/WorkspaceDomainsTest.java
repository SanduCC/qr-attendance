package io.github.sanducc.attendance.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanducc.attendance.course.WorkspaceDomains.InvalidDomainsException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Rule 7: parsing what the teacher types, and matching the ID token's hd claim. */
class WorkspaceDomainsTest {

    @Test
    void blankMeansNoRestriction() {
        assertThat(WorkspaceDomains.parse(null)).isEmpty();
        assertThat(WorkspaceDomains.parse("   ")).isEmpty();
        assertThat(WorkspaceDomains.allows(List.of(), null)).isTrue();
        assertThat(WorkspaceDomains.allows(List.of(), "university.ro")).isTrue();
        assertThat(WorkspaceDomains.allows(null, null)).isTrue();
    }

    @Test
    void teacherInputIsNormalized() {
        assertThat(WorkspaceDomains.parse("University.RO")).containsExactly("university.ro");
        assertThat(WorkspaceDomains.parse(" @university.ro, student.university.ro;stud.upb.ro  upb.ro. "))
                .containsExactly("university.ro", "student.university.ro", "stud.upb.ro", "upb.ro");
        assertThat(WorkspaceDomains.parse("upb.ro, UPB.ro, @upb.ro")).containsExactly("upb.ro");
        assertThat(WorkspaceDomains.parse("xn--mnchen-3ya.de")).containsExactly("xn--mnchen-3ya.de");
    }

    @Test
    void malformedDomainsAreRejectedWithAFriendlyMessage() {
        for (String bad : new String[] {"university", "ana@university.ro", "-upb.ro", "upb-.ro", "upb..ro",
                "univ ersity.ro,x", "https://upb.ro", "upb.ro/x", "*.upb.ro", "ünibuc.ro", "upb.ro'--",
                "a".repeat(64) + ".ro"}) {
            assertThatThrownBy(() -> WorkspaceDomains.parse(bad)).as(bad)
                    .isInstanceOf(InvalidDomainsException.class)
                    .hasMessageContaining("isn't a domain");
        }
    }

    @Test
    void consumerGmailCannotBeALockBecauseItNeverHasAnHdClaim() {
        assertThatThrownBy(() -> WorkspaceDomains.parse("gmail.com"))
                .isInstanceOf(InvalidDomainsException.class).hasMessageContaining("Gmail");
        assertThatThrownBy(() -> WorkspaceDomains.parse("upb.ro, @GoogleMail.com"))
                .isInstanceOf(InvalidDomainsException.class);
    }

    @Test
    void atMostFiveDomains() {
        assertThat(WorkspaceDomains.parse("a.ro b.ro c.ro d.ro e.ro")).hasSize(5);
        assertThatThrownBy(() -> WorkspaceDomains.parse("a.ro b.ro c.ro d.ro e.ro f.ro"))
                .isInstanceOf(InvalidDomainsException.class).hasMessageContaining("At most 5");
    }

    @Test
    void theServiceRevalidatesWhateverItIsGiven() {
        assertThat(WorkspaceDomains.normalize(Arrays.asList(" UPB.ro ", null, "", "@stud.upb.ro")))
                .containsExactly("upb.ro", "stud.upb.ro");
        assertThatThrownBy(() -> WorkspaceDomains.normalize(List.of("upb.ro,evil")))
                .isInstanceOf(InvalidDomainsException.class);
    }

    @Test
    void hdMustBeOneOfTheDomainsIgnoringCase() {
        List<String> allowed = WorkspaceDomains.parse("university.ro, student.university.ro");
        assertThat(WorkspaceDomains.allows(allowed, "university.ro")).isTrue();
        assertThat(WorkspaceDomains.allows(allowed, "University.RO")).isTrue();
        assertThat(WorkspaceDomains.allows(allowed, "student.university.ro")).isTrue();
        // No hd (consumer Gmail), another Workspace, a look-alike or a parent/child domain: refused.
        assertThat(WorkspaceDomains.allows(allowed, null)).isFalse();
        assertThat(WorkspaceDomains.allows(allowed, " ")).isFalse();
        assertThat(WorkspaceDomains.allows(allowed, "other.ro")).isFalse();
        assertThat(WorkspaceDomains.allows(allowed, "university.ro.evil.com")).isFalse();
        assertThat(WorkspaceDomains.allows(allowed, "cs.university.ro")).isFalse();
        assertThat(WorkspaceDomains.allows(allowed, "ro")).isFalse();
    }

    @Test
    void columnRoundTripAndMessages() {
        List<String> domains = List.of("upb.ro", "stud.upb.ro");
        assertThat(WorkspaceDomains.toColumn(domains)).isEqualTo("upb.ro,stud.upb.ro");
        assertThat(WorkspaceDomains.fromColumn("upb.ro,stud.upb.ro")).isEqualTo(domains);
        assertThat(WorkspaceDomains.toColumn(List.of())).isNull();
        assertThat(WorkspaceDomains.fromColumn(null)).isEmpty();
        assertThat(WorkspaceDomains.format(domains)).isEqualTo("upb.ro, stud.upb.ro");
        assertThat(WorkspaceDomains.describe(List.of())).isEmpty();
        assertThat(WorkspaceDomains.describe(List.of("upb.ro"))).isEqualTo("@upb.ro");
        assertThat(WorkspaceDomains.describe(domains)).isEqualTo("@upb.ro or @stud.upb.ro");
        assertThat(WorkspaceDomains.describe(List.of("a.ro", "b.ro", "c.ro"))).isEqualTo("@a.ro, @b.ro or @c.ro");
    }
}
