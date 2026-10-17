package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.course.WorkspaceDomains;
import io.github.sanducc.attendance.support.FakeGoogleConfiguration;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Anti-cheat rule 7 ("wrong domain"): a course can require the Google Workspace {@code hd} claim of the ID token.
 * Runs the real OAuth2 login with ID tokens minted by {@link FakeGoogleConfiguration}, with and without {@code hd}.
 */
class DomainLockTest extends IntegrationTestSupport {

    private static final String SCHOOL = "university.example.ro";

    /** The usual open fixture, with the course locked to {@code domains} (as the teacher would type them). */
    private Fixture lockedTo(String domains) {
        Fixture f = openFixture();
        courseService.updateCourse(f.teacherId(), f.courseId(), "Algoritmi", "CS101", WorkspaceDomains.parse(domains));
        return f;
    }

    private String confirmUrl(UUID sessionId) {
        return "/a/" + sessionId + "/confirm";
    }

    /** Scan → real Google sign-in as {@code email} (with {@code hd}, or none) → back on the confirm page. */
    private void scanAndSignIn(Browser phone, UUID sessionId, String email, String hd) throws Exception {
        scan(phone, sessionId, validToken(sessionId)).andExpect(status().isOk());
        signInWithGoogle(phone, email, hd).andExpect(redirectedUrl(confirmUrl(sessionId)));
    }

    /** The check-in POST; the sign-in comes only from the HTTP session. */
    private ResultActions checkIn(Browser phone, UUID sessionId) throws Exception {
        return phone.perform(post("/a/{id}/check-in", sessionId).with(csrf())
                .param("firstName", "Ana").param("lastName", "Popescu"));
    }

    private void assertRejected(ResultActions result, String expectedMessage) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Use your school account"))
                .andExpect(model().attribute("message", expectedMessage))
                .andExpect(model().attribute("actionUrl", "/oauth2/authorization/google"));
    }

    // ------------------------------------------------------------------ wrong domain

    @Test
    void consumerGmailAccountWithoutHdIsRejectedBeforeAnythingIsWritten() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana.popescu@gmail.com", null);

        // Told on the confirm page already, before typing a name...
        assertRejected(phone.perform(get(confirmUrl(f.sessionId()))),
                "This course only accepts @university.example.ro accounts.");
        // ...and the check-in itself is refused by the service, whatever the client sends.
        assertRejected(checkIn(phone, f.sessionId()), "This course only accepts @university.example.ro accounts.");

        assertThat(recordRepository.count()).isZero();
        assertThat(findStudent("sub-ana.popescu@gmail.com")).as("no student row either").isEmpty();
        assertThat(phone.pending()).as("the scan is kept, so the student can switch accounts").isNotNull();
    }

    @Test
    void anotherWorkspaceDomainIsRejected() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana@other-university.example.ro", "other-university.example.ro");

        assertRejected(checkIn(phone, f.sessionId()), "This course only accepts @university.example.ro accounts.");
        assertThat(recordRepository.count()).isZero();
    }

    @Test
    void aMatchingEmailSuffixWithoutTheHdClaimIsNotEnough() throws Exception {
        // Only the ID token's hd claim counts: an address that merely ends in the school domain doesn't.
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana@" + SCHOOL, null);

        assertRejected(checkIn(phone, f.sessionId()), "This course only accepts @university.example.ro accounts.");
        assertThat(recordRepository.count()).isZero();
    }

    @Test
    void aSubdomainOfTheAllowedDomainIsAnotherDomain() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana@cs." + SCHOOL, "cs." + SCHOOL);

        checkIn(phone, f.sessionId()).andExpect(status().isForbidden());
        assertThat(recordRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ right domain

    @Test
    void theRightHdChecksIn() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana@" + SCHOOL, SCHOOL);

        phone.perform(get(confirmUrl(f.sessionId())))
                .andExpect(status().isOk())
                .andExpect(view().name("student/confirm"));
        checkIn(phone, f.sessionId()).andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
        assertThat(findStudent("sub-ana@" + SCHOOL)).isPresent();
    }

    @Test
    void hdIsComparedIgnoringCase() throws Exception {
        Fixture f = lockedTo("@University.Example.RO");
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana@" + SCHOOL, "UNIVERSITY.example.ro");

        checkIn(phone, f.sessionId()).andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));
        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    @Test
    void anyOfSeveralDomainsIsAccepted() throws Exception {
        Fixture f = lockedTo(SCHOOL + ", student." + SCHOOL);
        Browser student = newBrowser();
        scanAndSignIn(student, f.sessionId(), "ana@student." + SCHOOL, "student." + SCHOOL);
        checkIn(student, f.sessionId()).andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));

        Browser gmail = newBrowser();
        scanAndSignIn(gmail, f.sessionId(), "bob@gmail.com", null);
        assertRejected(checkIn(gmail, f.sessionId()),
                "This course only accepts @university.example.ro or @student.university.example.ro accounts.");

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    @Test
    void withoutALockAnyGoogleAccountChecksInAsBefore() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana.popescu@gmail.com", null);

        checkIn(phone, f.sessionId()).andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));
        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }

    @Test
    void theStudentCanSwitchToTheSchoolAccountWithinTheGracePeriod() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana.popescu@gmail.com", null);
        assertRejected(checkIn(phone, f.sessionId()), "This course only accepts @university.example.ro accounts.");

        // "Choose another Google account": signing in again keeps the scan (and its device binding).
        clock.advance(Duration.ofSeconds(60));
        signInWithGoogle(phone, "ana@" + SCHOOL, SCHOOL).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        checkIn(phone, f.sessionId()).andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
        assertThat(findStudent("sub-ana.popescu@gmail.com")).isEmpty();
    }

    @Test
    void theGracePeriodStillApplies() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scanAndSignIn(phone, f.sessionId(), "ana.popescu@gmail.com", null);

        clock.advance(Duration.ofMinutes(4));
        signInWithGoogle(phone, "ana@" + SCHOOL, SCHOOL);
        checkIn(phone, f.sessionId()).andExpect(status().isGone());
        assertThat(recordRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ hint in the authorization request

    @Test
    void googleIsAskedForTheSchoolAccountOfALockedCourse() throws Exception {
        Fixture locked = lockedTo(SCHOOL);
        Browser phone = newBrowser();
        scan(phone, locked.sessionId(), validToken(locked.sessionId())).andExpect(status().isOk());
        MultiValueMap<String, String> params = queryParams(authorizationRedirect(phone));
        assertThat(params.getFirst("hd")).isEqualTo(SCHOOL);
        assertThat(params.getFirst("prompt")).isEqualTo("select_account");

        Fixture several = lockedTo(SCHOOL + " student." + SCHOOL);
        Browser other = newBrowser();
        scan(other, several.sessionId(), validToken(several.sessionId())).andExpect(status().isOk());
        assertThat(queryParams(authorizationRedirect(other)).getFirst("hd")).as("any Workspace account").isEqualTo("*");
    }

    @Test
    void noHintWithoutALockOrWithoutAScan() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        assertThat(queryParams(authorizationRedirect(phone))).doesNotContainKeys("hd", "prompt");

        assertThat(queryParams(authorizationRedirect(newBrowser()))).doesNotContainKeys("hd", "prompt")
                .containsKeys("state", "nonce", "client_id");
    }

    @Test
    void landingPageTellsStudentsWhichAccountToUse() throws Exception {
        Fixture f = lockedTo(SCHOOL);
        scan(newBrowser(), f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("allowedDomains", "@" + SCHOOL));
    }

    private static MultiValueMap<String, String> queryParams(String url) {
        MultiValueMap<String, String> raw = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
        MultiValueMap<String, String> decoded = new LinkedMultiValueMap<>();
        raw.forEach((k, values) -> values.forEach(v ->
                decoded.add(k, v == null ? null : URLDecoder.decode(v, StandardCharsets.UTF_8))));
        return decoded;
    }
}
