package io.github.sanducc.attendance.emailsignin;

import static io.github.sanducc.attendance.support.FakeMailConfiguration.CapturingMailSender.html;
import static io.github.sanducc.attendance.support.FakeMailConfiguration.CapturingMailSender.plainText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.checkin.Provider;
import io.github.sanducc.attendance.checkin.Student;
import io.github.sanducc.attendance.common.EmailAuthenticationToken;
import io.github.sanducc.attendance.config.AppOidcUserService;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Email sign-in (#52) end to end: scan → email → code (from the fake inbox) → confirm name → check in, and every
 * way to abuse it: wrong, exhausted, expired, reused and foreign codes, rate limits, roles, and what gets logged.
 * The scan rules (QR token, grace period, one check-in per account and per device) are the existing ones.
 */
class EmailSignInFlowTest extends IntegrationTestSupport {

    private static final String ANA = "ana.pop+ap@example.ro";
    private static final String SECURITY_CONTEXT = "SPRING_SECURITY_CONTEXT";
    private static final String TOO_MANY_SENDS = "Too many codes were requested. Wait a few minutes, then try again.";

    private static String confirmUrl(UUID sessionId) {
        return "/a/" + sessionId + "/confirm";
    }

    private static String doneUrl(UUID sessionId) {
        return "/a/" + sessionId + "/done";
    }

    /** A well-formed code that isn't {@code code}. */
    private static String otherCode(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private static SecurityContext securityContext(Browser browser) {
        Session session = browser.storedSession();
        return session == null ? null : session.getAttribute(SECURITY_CONTEXT);
    }

    private ResultActions postEmail(Browser browser, String email) throws Exception {
        return browser.perform(post("/login/email").with(csrf()).param("email", email));
    }

    private ResultActions resend(Browser browser) throws Exception {
        return browser.perform(post("/login/email/resend").with(csrf()));
    }

    private Set<String> codeKeys() {
        return redis.keys(EmailCodeStore.KEY_PREFIX + "*");
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void scanEmailCodeConfirmCheckInThenASecondCheckInIsAlreadyCheckedIn() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        scan(phone, f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(view().name("student/landing"))
                .andExpect(model().attribute("emailSignIn", true))
                .andExpect(content().string(containsString("href=\"/login/email\"")));
        clock.advance(Duration.ofSeconds(20));

        // Typed with spaces and capitals: normalized, dots and +tag kept.
        String code = requestEmailCode(phone, "  Ana.Pop+AP@Example.RO ");
        phone.perform(get("/login/email/code"))
                .andExpect(status().isOk())
                .andExpect(view().name("student/email-code"))
                .andExpect(model().attribute("email", ANA));
        assertThat(securityContext(phone)).as("not signed in by asking for a code").isNull();

        String sessionBefore = phone.sessionId();
        clock.advance(Duration.ofSeconds(40));
        enterEmailCode(phone, code.substring(0, 3) + " " + code.substring(3))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        assertThat(phone.sessionId()).as("session id rotated on sign-in").isNotEqualTo(sessionBefore);
        assertThat(phone.pending()).as("the scan survives the sign-in").isNotNull();
        assertThat(securityContext(phone).getAuthentication()).isInstanceOf(EmailAuthenticationToken.class);
        assertThat(codeKeys()).as("the code is used up").isEmpty();

        confirm(phone, f.sessionId(), sessionLogin())
                .andExpect(status().isOk())
                .andExpect(view().name("student/confirm"))
                .andExpect(model().attribute("registered", false))
                .andExpect(model().attribute("email", ANA))
                .andExpect(model().attribute("nameForm", hasProperty("firstName", nullValue())));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Ana", "Pop")
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(doneUrl(f.sessionId())))
                .andExpect(flash().attribute("justCheckedIn", true));
        done(phone, f.sessionId(), sessionLogin())
                .andExpect(status().isOk())
                .andExpect(model().attribute("firstName", "Ana"))
                .andExpect(model().attribute("lastName", "Pop"));

        Student student = studentRepository.findByProviderAndProviderSubject(Provider.EMAIL, ANA).orElseThrow();
        assertThat(student.getEmail()).isEqualTo(ANA);
        assertThat(recordRepository.findAll()).hasSize(1);

        // Second attempt: the sign-in is kept, so a new scan goes straight on, and is refused as a duplicate.
        clock.advance(Duration.ofSeconds(30));
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        confirm(phone, f.sessionId(), sessionLogin()).andExpect(redirectedUrl(doneUrl(f.sessionId())));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Someone", "Else")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())))
                .andExpect(flash().attributeCount(0));
        assertThat(recordRepository.findAll()).hasSize(1);
        assertThat(studentRepository.findById(student.getId()).orElseThrow().getFirstName())
                .as("names stay locked").isEqualTo("Ana");
    }

    @Test
    void theMailHoldsTheCodeItsValidityAndNoLinksAndRedisOnlyAHash() throws Exception {
        String code = requestEmailCode(newBrowser(), ANA);

        MimeMessage message = mail.sentTo(ANA).getLast();
        assertThat(message.getSubject()).contains(code).contains("QR Attendance");
        assertThat(message.getFrom()[0].toString()).contains("attendance@test.example.ro");
        assertThat(plainText(message)).contains(code).contains("10 minutes").contains("didn't ask for this code")
                .contains("QR Attendance").doesNotContain("http");
        assertThat(html(message)).contains(code).contains("10 minutes").doesNotContain("http")
                .doesNotContainIgnoringCase("<img").doesNotContainIgnoringCase("href");

        Set<String> keys = codeKeys();
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        assertThat(key).doesNotContain(ANA).doesNotContain("example");
        Map<Object, Object> stored = redis.opsForHash().entries(key);
        assertThat(stored).containsOnlyKeys("h", "n", "e").containsEntry("n", "0");
        assertThat(String.valueOf(stored.get("h"))).as("a peppered hash, not the code").matches("[0-9a-f]{64}");
        assertThat(stored.values()).noneMatch(value -> String.valueOf(value).equals(code));
        assertThat(redis.getExpire(key)).as("TTL in seconds").isBetween(590L, 600L);
    }

    @Test
    void withoutAScanTheStudentLandsOnTheHomePage() throws Exception {
        signInWithEmail(newBrowser(), ANA).andExpect(redirectedUrl("/"));
    }

    // ------------------------------------------------------------------ wrong, exhausted, expired, reused codes

    @Test
    void aWrongCodeIsRefusedWithTheTriesLeftAndAMalformedOneDoesNotCount() throws Exception {
        Browser browser = newBrowser();
        String code = requestEmailCode(browser, ANA);

        enterEmailCode(browser, "12ab")
                .andExpect(status().isBadRequest())
                .andExpect(model().attribute("error", "Enter the 6-digit code from the email."));
        enterEmailCode(browser, otherCode(code))
                .andExpect(status().isBadRequest())
                .andExpect(model().attribute("error", "That code isn't right. 4 tries left."));
        assertThat(securityContext(browser)).isNull();

        enterEmailCode(browser, code).andExpect(redirectedUrl("/"));
    }

    @Test
    void fiveWrongCodesInvalidateTheCode() throws Exception {
        Browser browser = newBrowser();
        String code = requestEmailCode(browser, ANA);
        String wrong = otherCode(code);

        for (int left = 4; left >= 1; left--) {
            enterEmailCode(browser, wrong).andExpect(status().isBadRequest())
                    .andExpect(model().attribute("error",
                            "That code isn't right. " + left + (left == 1 ? " try" : " tries") + " left."));
        }
        enterEmailCode(browser, wrong).andExpect(status().isBadRequest())
                .andExpect(model().attribute("error",
                        "Too many wrong codes, so this one no longer works. Send a new code."));
        assertThat(codeKeys()).isEmpty();

        // Even the right code is useless now.
        enterEmailCode(browser, code).andExpect(status().isBadRequest())
                .andExpect(model().attribute("error",
                        "This code has expired or was replaced by a newer one. Send a new code."));
        assertThat(securityContext(browser)).isNull();
    }

    @Test
    void anExpiredCodeIsRefused() throws Exception {
        Browser browser = newBrowser();
        String code = requestEmailCode(browser, ANA);

        clock.advance(Duration.ofMinutes(10));
        enterEmailCode(browser, code).andExpect(status().isBadRequest())
                .andExpect(model().attribute("error",
                        "This code has expired or was replaced by a newer one. Send a new code."));
        assertThat(securityContext(browser)).isNull();
        assertThat(codeKeys()).isEmpty();
    }

    @Test
    void aCodeWorksOnce() throws Exception {
        Browser browser = newBrowser();
        String code = requestEmailCode(browser, ANA);
        enterEmailCode(browser, code).andExpect(redirectedUrl("/"));

        // Signing out and typing it again: the code page has nothing to check it against any more.
        Browser later = browser.sameDeviceNewSession();
        enterEmailCode(later, code).andExpect(redirectedUrl("/login/email"));
        later.perform(get("/login/email/code")).andExpect(redirectedUrl("/login/email"));
        enterEmailCode(browser, code).andExpect(redirectedUrl("/login/email"));
    }

    @Test
    void aCodeFromAnotherBrowserSessionIsRejected() throws Exception {
        Browser phone = newBrowser();
        String phoneCode = requestEmailCode(phone, ANA);

        // A friend's phone, or the mail app's own browser: no code was asked for in this session.
        Browser other = newBrowser();
        enterEmailCode(other, phoneCode).andExpect(redirectedUrl("/login/email"));
        // Asking for a code for the same address there doesn't make the first code valid there.
        String otherCode = requestEmailCode(other, ANA);
        assumeThat(otherCode).isNotEqualTo(phoneCode);
        enterEmailCode(other, phoneCode).andExpect(status().isBadRequest())
                .andExpect(model().attribute("error", "That code isn't right. 4 tries left."));
        assertThat(securityContext(other)).isNull();

        // The phone that asked for it can still use it.
        enterEmailCode(phone, phoneCode).andExpect(redirectedUrl("/"));
    }

    @Test
    void aNewCodeWaitsForTheCooldownAndReplacesTheOldOne() throws Exception {
        Browser browser = newBrowser();
        String first = requestEmailCode(browser, ANA);
        browser.perform(get("/login/email/code")).andExpect(model().attribute("cooldownSeconds", 30L));

        // A double tap on "Email me a code" doesn't send a second mail.
        postEmail(browser, ANA).andExpect(redirectedUrl("/login/email/code"));
        resend(browser).andExpect(status().isTooManyRequests())
                .andExpect(model().attribute("error", "Wait 30 seconds before sending a new code."));
        assertThat(mail.sentTo(ANA)).hasSize(1);

        clock.advance(Duration.ofSeconds(31));
        resend(browser).andExpect(redirectedUrl("/login/email/code")).andExpect(flash().attribute("resent", true));
        assertThat(mail.sentTo(ANA)).hasSize(2);
        String second = mail.lastCodeTo(ANA);
        assumeThat(second).isNotEqualTo(first);

        enterEmailCode(browser, first).andExpect(status().isBadRequest());
        enterEmailCode(browser, second).andExpect(redirectedUrl("/"));
    }

    // ------------------------------------------------------------------ anti-abuse

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-email", "ana@", "@example.ro", "ana@localhost", "ana@@example.ro",
            "ana pop@example.ro", "ana@example..ro", "ana..pop@example.ro", ".ana@example.ro", "ana@-example.ro",
            "ană@example.ro", "ana@example.ro\r\nBcc: victim@example.ro"})
    void invalidAddressesGetNoMail(String email) throws Exception {
        Browser browser = newBrowser();
        browser.perform(get("/login/email")).andExpect(status().isOk());
        postEmail(browser, email).andExpect(status().isBadRequest())
                .andExpect(view().name("student/email"))
                .andExpect(model().attribute("error", "Enter a valid email address, e.g. ana.popescu@example.ro."));
        assertThat(mail.sent()).isEmpty();
        assertThat(codeKeys()).isEmpty();
    }

    @Test
    void thePageSaysTheSameWhetherOrNotTheAddressHasAnAccount() throws Exception {
        studentRepository.saveAndFlush(new Student(Provider.EMAIL, "known@example.ro", "known@example.ro", "Ana",
                "Pop", clock.instant()));
        Browser known = newBrowser();
        Browser unknown = newBrowser();
        requestEmailCode(known, "known@example.ro");
        requestEmailCode(unknown, "unknown@example.ro");

        String knownPage = known.perform(get("/login/email/code")).andReturn().getResponse().getContentAsString();
        String unknownPage = unknown.perform(get("/login/email/code")).andReturn().getResponse().getContentAsString();
        assertThat(withoutVolatileParts(knownPage, "known@example.ro"))
                .isEqualTo(withoutVolatileParts(unknownPage, "unknown@example.ro"));
    }

    private static String withoutVolatileParts(String page, String email) {
        return page.replace(email, "EMAIL").replaceAll("name=\"_csrf\"\\s+value=\"[^\"]+\"", "CSRF");
    }

    @Test
    void codeSendsAreLimitedPerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            requestEmailCode(newBrowser(), ANA);
        }
        Browser fourth = newBrowser();
        fourth.perform(get("/login/email"));
        postEmail(fourth, ANA).andExpect(status().isTooManyRequests())
                .andExpect(model().attribute("error", TOO_MANY_SENDS));
        assertThat(mail.sentTo(ANA)).hasSize(3);

        // Another address from the same place is fine, and so is this one in the next window.
        requestEmailCode(fourth, "bogdan@example.ro");
        clock.advance(Duration.ofMinutes(15));
        requestEmailCode(newBrowser(), ANA);
    }

    @Test
    void codeSendsAreLimitedPerDevice() throws Exception {
        Browser phone = newBrowser();
        for (int i = 1; i <= 5; i++) {
            requestEmailCode(phone, "student" + i + "@example.ro");
        }
        postEmail(phone, "student6@example.ro").andExpect(status().isTooManyRequests())
                .andExpect(model().attribute("error", TOO_MANY_SENDS));
        // A new HTTP session on the same phone doesn't reset the device counter.
        Browser sameDevice = phone.sameDeviceNewSession();
        sameDevice.perform(get("/login/email"));
        postEmail(sameDevice, "student7@example.ro").andExpect(status().isTooManyRequests());
        assertThat(mail.sent()).hasSize(5);
    }

    @Test
    void codeEntriesAreLimitedPerSession() throws Exception {
        Browser browser = newBrowser();
        String first = requestEmailCode(browser, ANA);
        for (int i = 0; i < 5; i++) {
            enterEmailCode(browser, otherCode(first)).andExpect(status().isBadRequest());
        }
        clock.advance(Duration.ofSeconds(31));
        resend(browser).andExpect(redirectedUrl("/login/email/code"));
        String second = mail.lastCodeTo(ANA);
        for (int i = 0; i < 5; i++) {
            enterEmailCode(browser, otherCode(second)).andExpect(status().isBadRequest());
        }
        clock.advance(Duration.ofSeconds(31));
        resend(browser).andExpect(redirectedUrl("/login/email/code"));
        String third = mail.lastCodeTo(ANA);

        // Ten entries in this window: even the right code is refused until the window is over.
        enterEmailCode(browser, third).andExpect(status().isTooManyRequests())
                .andExpect(model().attribute("error", "Too many attempts. Wait a few minutes, then try again."));
        assertThat(securityContext(browser)).isNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aSendFailureSaysTryAgainAndKeepsNoCode(CapturedOutput output) throws Exception {
        mail.failWith(() -> new MailSendException("550 5.1.1 Invalid Addresses: " + ANA));
        Browser browser = newBrowser();
        browser.perform(get("/login/email"));
        postEmail(browser, ANA).andExpect(status().isServiceUnavailable())
                .andExpect(view().name("student/email"))
                .andExpect(model().attribute("error", "We couldn't send the code. Please try again."))
                .andExpect(model().attribute("email", ANA));
        assertThat(codeKeys()).isEmpty();
        browser.perform(get("/login/email/code")).andExpect(redirectedUrl("/login/email"));
        assertThat(output.getAll()).contains("a***@example.ro").doesNotContain(ANA);

        mail.reset();
        requestEmailCode(browser, ANA);
    }

    // ------------------------------------------------------------------ roles and identity

    @Test
    void anEmailLoginNeverGetsTheTeacherOrAdminRole() throws Exception {
        createTeacher("prof.ionescu@university.example.ro");
        Browser teacherAddress = newBrowser();
        signInWithEmail(teacherAddress, "prof.ionescu@university.example.ro").andExpect(redirectedUrl("/"));
        teacherAddress.perform(get("/teacher/courses")).andExpect(status().isForbidden());
        teacherAddress.perform(get("/admin/teachers")).andExpect(status().isForbidden());
        assertThat(roles(teacherAddress)).containsExactly(AppOidcUserService.ROLE_STUDENT);

        // ADMIN_EMAILS (application-test.yml) has this address, in this case.
        Browser adminAddress = newBrowser();
        signInWithEmail(adminAddress, "Head.Admin@university.example.ro").andExpect(redirectedUrl("/"));
        adminAddress.perform(get("/admin/teachers")).andExpect(status().isForbidden());
        adminAddress.perform(get("/teacher/courses")).andExpect(status().isForbidden());
        assertThat(roles(adminAddress)).containsExactly(AppOidcUserService.ROLE_STUDENT);
        assertThat(teacherRepository.findAll()).as("no teacher row created or touched").hasSize(1)
                .allSatisfy(t -> assertThat(t.getLastLoginAt()).isNull());
    }

    private static List<String> roles(Browser browser) {
        return securityContext(browser).getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toList());
    }

    @Test
    void aGoogleAndAnEmailStudentWithTheSameAddressAreDifferentAccounts() throws Exception {
        Fixture f = openFixture();
        String address = "ana@example.ro";

        Browser googlePhone = newBrowser();
        scan(googlePhone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithGoogle(googlePhone, address).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        postCheckIn(googlePhone, f.sessionId(), sessionLogin(), "Ana", "Google")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));

        Browser emailPhone = newBrowser();
        scan(emailPhone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithEmail(emailPhone, address).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        confirm(emailPhone, f.sessionId(), sessionLogin()).andExpect(model().attribute("registered", false));
        postCheckIn(emailPhone, f.sessionId(), sessionLogin(), "Ana", "Email")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())))
                .andExpect(flash().attribute("justCheckedIn", true));

        Student google = findStudent("sub-" + address).orElseThrow();
        Student email = studentRepository.findByProviderAndProviderSubject(Provider.EMAIL, address).orElseThrow();
        assertThat(google.getId()).isNotEqualTo(email.getId());
        assertThat(google.getEmail()).isEqualTo(email.getEmail()).isEqualTo(address);
        assertThat(google.getLastName()).isEqualTo("Google");
        assertThat(email.getLastName()).isEqualTo("Email");
        assertThat(recordRepository.findAll()).hasSize(2);
    }

    // ------------------------------------------------------------------ the scan rules still apply

    @Test
    void theGracePeriodSinceTheScanStillApplies() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(3).plusSeconds(1));
        signInWithEmail(phone, ANA).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        confirm(phone, f.sessionId(), sessionLogin()).andExpect(status().isGone())
                .andExpect(model().attribute("title", "Please scan again"));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Ana", "Pop").andExpect(status().isGone());
        assertThat(recordRepository.count()).isZero();

        // Scanning again is enough: the email sign-in is kept.
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Ana", "Pop")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));
    }

    @Test
    void onePhoneStillChecksInOneStudent() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithGoogle(phone, "bogdan@example.ro").andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Bogdan", "Ionescu")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));

        // Same phone, signed out, now a friend's address.
        Browser friend = phone.sameDeviceNewSession();
        scan(friend, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithEmail(friend, ANA).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        postCheckIn(friend, f.sessionId(), sessionLogin(), "Ana", "Pop").andExpect(status().isConflict())
                .andExpect(model().attribute("title", "This phone was already used"));
        assertThat(recordRepository.count()).isOne();
    }

    // ------------------------------------------------------------------ logs

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logsContainNeitherTheCodeNorTheFullAddress(CapturedOutput output) throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        String code = requestEmailCode(phone, ANA);
        // Every log line carries the process id: a code equal to it would be a false alarm.
        assumeThat(code).isNotEqualTo(Long.toString(ProcessHandle.current().pid()));
        enterEmailCode(phone, otherCode(code)).andExpect(status().isBadRequest());
        enterEmailCode(phone, code).andExpect(redirectedUrl(confirmUrl(f.sessionId())));
        postCheckIn(phone, f.sessionId(), sessionLogin(), "Ana", "Pop")
                .andExpect(redirectedUrl(doneUrl(f.sessionId())));

        String logs = output.getAll();
        assertThat(logs).contains("Sign-in code sent to a***@example.ro")
                .doesNotContain(ANA).doesNotContain("ana.pop");
        assertThat(Pattern.compile("(?<!\\d)" + code + "(?!\\d)").matcher(logs).find())
                .as("the code appears in the logs").isFalse();
    }
}
