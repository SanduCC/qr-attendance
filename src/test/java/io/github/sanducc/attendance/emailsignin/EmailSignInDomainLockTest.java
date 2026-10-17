package io.github.sanducc.attendance.emailsignin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.checkin.Provider;
import io.github.sanducc.attendance.course.WorkspaceDomains;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Domain lock (rule 7, #4) with the email sign-in (#52): the verified address's domain is the proof, and it must
 * be exactly one of the course's domains (any case). A wrong address gets no code; a sign-in made elsewhere with
 * a wrong address is still refused at check-in.
 */
class EmailSignInDomainLockTest extends IntegrationTestSupport {

    private static final String SCHOOL = "university.example.ro";
    private static final String WRONG_DOMAIN =
            "This course only accepts @university.example.ro addresses. Use your school email address.";

    private Fixture lockedTo(String domains) {
        Fixture f = openFixture();
        courseService.updateCourse(f.teacherId(), f.courseId(), "Algoritmi", "CS101", WorkspaceDomains.parse(domains));
        return f;
    }

    private static String confirmUrl(UUID sessionId) {
        return "/a/" + sessionId + "/confirm";
    }

    @ParameterizedTest
    @ValueSource(strings = {"ana.popescu@gmail.com", "ana@student.university.example.ro",
            "ana@university.example.ro.evil.example", "ana@example.ro"})
    void anAddressOutsideTheCoursesDomainsGetsNoCode(String email) throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        phone.perform(get("/login/email")).andExpect(status().isOk())
                .andExpect(model().attribute("allowedDomains", "@university.example.ro"));
        phone.perform(post("/login/email").with(csrf()).param("email", email))
                .andExpect(status().isForbidden())
                .andExpect(view().name("student/email"))
                .andExpect(model().attribute("error", WRONG_DOMAIN));
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void anAddressOnTheCoursesDomainChecksInWhateverItsCase() throws Exception {
        Fixture f = lockedTo("Student.University.example.ro, " + SCHOOL);
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        signInWithEmail(phone, "Ana.Popescu@University.Example.RO")
                .andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        confirm(phone, f.sessionId(), sessionLogin()).andExpect(status().isOk());
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Ana", "Popescu")
                .andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));

        assertThat(studentRepository.findByProviderAndProviderSubject(Provider.EMAIL,
                "ana.popescu@university.example.ro")).isPresent();
        assertThat(recordRepository.count()).isOne();
    }

    @Test
    void theCheckInRefusesAWrongAddressSignedInBeforeTheScan() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        // Signed in from the login page, so no course was known yet and the code went out.
        signInWithEmail(phone, "ana.popescu@gmail.com").andExpect(redirectedUrl("/"));

        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        phone.perform(get(confirmUrl(f.sessionId())))
                .andExpect(status().isForbidden())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Use your school address"))
                .andExpect(model().attribute("message", "This course only accepts @university.example.ro addresses."))
                .andExpect(model().attribute("actionUrl", "/login/email"));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Ana", "Popescu").andExpect(status().isForbidden());

        assertThat(recordRepository.count()).isZero();
        assertThat(studentRepository.count()).as("no student row either").isZero();
        assertThat(phone.pending()).as("the scan is kept for another address").isNotNull();
    }

    @Test
    void aGoogleAccountOnTheWrongDomainIsOfferedTheEmailSignIn() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithGoogle(phone, "ana.popescu@gmail.com").andExpect(redirectedUrl(confirmUrl(f.sessionId())));

        phone.perform(get(confirmUrl(f.sessionId())))
                .andExpect(status().isForbidden())
                .andExpect(model().attribute("actionUrl", "/oauth2/authorization/google"))
                .andExpect(model().attribute("secondaryUrl", "/login/email"));
    }
}
