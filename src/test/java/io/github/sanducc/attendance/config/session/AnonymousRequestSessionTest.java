package io.github.sanducc.attendance.config.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.checkin.AttendanceRecord;
import io.github.sanducc.attendance.session.SessionStatus;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Anonymous requests don't create HTTP sessions (issue #44): every session is a Redis write (Upstash's free command
 * budget) and a {@code SESSION} cookie. Only the app's own deep links save the pre-login request, the scan landing
 * stores its pending check-in, and "Sign in with Google" stores the OAuth2 authorization request. Bots probing
 * random paths, and the public pages, leave nothing behind. Nor do anonymous POSTs (issue #47): CSRF rejects them
 * without storing a token in a new session, and works exactly as before for every request that has a session.
 */
class AnonymousRequestSessionTest extends IntegrationTestSupport {

    private static final String SESSION_KEYS = SessionConfig.NAMESPACE + ":*";
    private static final String KEY_PREFIX = SessionConfig.NAMESPACE + ":sessions:";
    /** {@link org.springframework.security.web.savedrequest.HttpSessionRequestCache}'s default attribute. */
    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";
    private static final String SAVED_REQUEST_FIELD = "sessionAttr:" + SAVED_REQUEST;
    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    /** The hidden field Thymeleaf adds to every {@code th:action} form, and the display page's meta tags. */
    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");
    private static final Pattern CSRF_META = Pattern.compile("name=\"_csrf\"\\s+content=\"([^\"]+)\"");
    private static final Pattern CSRF_HEADER_META = Pattern.compile("name=\"_csrf_header\"\\s+content=\"([^\"]+)\"");
    /** Base64 of "made-up": a token value no session ever issued. */
    private static final String MADE_UP_TOKEN = "bWFkZS11cA";
    private static final String STUDENT = "ana@student.example.ro";

    // ------------------------------------------------------------------ bots and unknown paths

    @ParameterizedTest
    @ValueSource(strings = {"/wp-login.php", "/.env", "/nonexistent-page", "/sitemap.xml", "/a", "/admin",
            "/admin/login.php", "/teacher", "/xmlrpc.php"})
    void unknownPathsRedirectToSignInWithoutASession(String path) throws Exception {
        expectNoSession(anonymous(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login"))));
    }

    @Test
    void randomPathsCreateNoSession() throws Exception {
        for (int i = 0; i < 5; i++) {
            expectNoSession(anonymous(get("/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".php"))
                    .andExpect(status().is3xxRedirection()));
        }
    }

    @Test
    void robotsTxtIsPublicCacheableAndStateless() throws Exception {
        expectNoSession(anonymous(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string("User-agent: *\nDisallow: /\n"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("public")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=86400"))));
    }

    @Test
    void apiCallsWithoutASignInCreateNoSession() throws Exception {
        String records = "/sessions/" + UUID.randomUUID() + "/records";
        expectNoSession(mvc.perform(get(records).header(HttpHeaders.ACCEPT, "application/json")));
        expectNoSession(mvc.perform(get(records).header("X-Requested-With", "XMLHttpRequest")));
        expectNoSession(mvc.perform(get(records).header(HttpHeaders.ACCEPT, "text/event-stream")));
    }

    // ------------------------------------------------------------------ state-changing requests (issue #47)

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /xmlrpc.php",
            "POST, /wp-login.php",
            "POST, /",
            "POST, /login",
            "POST, /logout",
            "POST, /a/{id}/check-in",
            "PATCH, /a/{id}/check-in",
            "POST, /sessions/{id}/close",
            "POST, /sessions/{id}/records",
            "DELETE, /sessions/{id}/records/1",
            "POST, /teacher/courses",
            "PUT, /teacher/courses",
            "POST, /admin/teachers"})
    void stateChangingRequestsWithoutASessionAreForbiddenAndCreateNoSession(String method, String path)
            throws Exception {
        Fixture f = openFixture();
        expectNoSession(anonymous(request(HttpMethod.valueOf(method), path, f.sessionId()))
                .andExpect(status().isForbidden()));
        assertThat(recordRepository.count()).isZero();
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void aMadeUpTokenWithoutASessionIsForbiddenAndCreatesNoSession() throws Exception {
        Fixture f = openFixture();
        expectNoSession(anonymous(checkIn(f.sessionId()).param("_csrf", MADE_UP_TOKEN))
                .andExpect(status().isForbidden()));
        expectNoSession(anonymous(post("/sessions/{id}/close", f.sessionId()).header("X-CSRF-TOKEN", MADE_UP_TOKEN))
                .andExpect(status().isForbidden()));
        assertThat(recordRepository.count()).isZero();
        assertThat(sessionRepository.findById(f.sessionId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.OPEN);
    }

    @Test
    void aPostWithAStaleSessionCookieIsForbiddenAndCreatesNoSession() throws Exception {
        // E.g. a phone whose session expired: the cookie names a session Redis no longer has.
        Fixture f = openFixture();
        String stale = Base64.getEncoder()
                .encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = anonymous(checkIn(f.sessionId()).cookie(new Cookie(SESSION_COOKIE, stale)))
                .andExpect(status().isForbidden())
                .andReturn().getResponse();
        Cookie session = response.getCookie(SESSION_COOKIE);
        assertThat(session == null || session.getMaxAge() == 0).as("no new session cookie, at most a cleared one")
                .isTrue();
        assertThat(redis.keys(SESSION_KEYS)).isEmpty();
    }

    @Test
    void scanSignInConfirmCheckInPassesWithTheRenderedTokenAndRefusesOthersOnTheWay() throws Exception {
        Fixture f = openFixture();
        UUID id = f.sessionId();
        Browser phone = newBrowser();
        scan(phone, id, validToken(id)).andExpect(status().isOk());
        signInWithGoogle(phone, STUDENT).andExpect(redirectedUrl("/a/" + id + "/confirm"));
        String sessionId = phone.sessionId();
        String token = token(CSRF_INPUT, phone.perform(get("/a/{id}/confirm", id))
                .andExpect(status().isOk())
                .andExpect(view().name("student/confirm"))
                .andReturn());

        // A classmate's token is a real one, but tied to their session, not this one.
        Browser classmate = newBrowser();
        scan(classmate, id, validToken(id)).andExpect(status().isOk());
        signInWithGoogle(classmate, "bogdan@student.example.ro").andExpect(status().is3xxRedirection());
        String classmatesToken = token(CSRF_INPUT, classmate.perform(get("/a/{id}/confirm", id))
                .andExpect(status().isOk())
                .andReturn());

        phone.perform(checkIn(id)).andExpect(status().isForbidden());
        phone.perform(checkIn(id).param("_csrf", MADE_UP_TOKEN)).andExpect(status().isForbidden());
        phone.perform(checkIn(id).param("_csrf", classmatesToken)).andExpect(status().isForbidden());
        phone.perform(checkIn(id).header("X-CSRF-TOKEN", classmatesToken)).andExpect(status().isForbidden());
        assertThat(recordRepository.count()).isZero();

        // The refusals left the signed-in session as it was: same id, still in Redis, pending scan kept.
        assertThat(phone.sessionId()).isEqualTo(sessionId);
        assertThat(redis.hasKey(KEY_PREFIX + sessionId)).isTrue();
        assertThat(phone.pending()).isNotNull();

        phone.perform(checkIn(id).param("_csrf", token)).andExpect(redirectedUrl("/a/" + id + "/done"));
        phone.perform(get("/a/{id}/done", id)).andExpect(status().isOk()).andExpect(view().name("student/done"));
        assertThat(recordRepository.countBySessionId(id)).isEqualTo(1);
        assertThat(findStudent("sub-" + STUDENT)).isPresent();
    }

    @Test
    void aTeachersFormPostsAndFetchCallsPassWithTheRenderedToken() throws Exception {
        Fixture f = openFixture();
        UUID id = f.sessionId();
        String email = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        Browser laptop = newBrowser();
        signInWithGoogle(laptop, email).andExpect(redirectedUrl("/teacher/courses"));
        MvcResult display = laptop.perform(get("/sessions/{id}/display", id))
                .andExpect(status().isOk())
                .andReturn();
        String headerName = token(CSRF_HEADER_META, display);
        String fetchToken = token(CSRF_META, display);
        String formToken = token(CSRF_INPUT, display);

        // display.js: a manual entry and its removal, with the token in the header.
        laptop.perform(xhr(post("/sessions/{id}/records", id)).param("firstName", "Carmen").param("lastName", "Dobre"))
                .andExpect(status().isForbidden());
        laptop.perform(xhr(post("/sessions/{id}/records", id)).header(headerName, fetchToken)
                        .param("firstName", "Carmen").param("lastName", "Dobre"))
                .andExpect(status().isCreated());
        long recordId = recordRepository.findAll().stream()
                .filter(r -> r.getSessionId().equals(id))
                .map(AttendanceRecord::getId)
                .findFirst().orElseThrow();
        laptop.perform(xhr(delete("/sessions/{id}/records/{rid}", id, recordId)))
                .andExpect(status().isForbidden());
        laptop.perform(xhr(delete("/sessions/{id}/records/{rid}", id, recordId)).header(headerName, fetchToken))
                .andExpect(status().isNoContent());
        assertThat(recordRepository.existsById(recordId)).isFalse();

        // The "Close session" form, with its hidden _csrf field.
        laptop.perform(post("/sessions/{id}/close", id).param("_csrf", MADE_UP_TOKEN))
                .andExpect(status().isForbidden());
        assertThat(sessionRepository.findById(id).orElseThrow().getStatus()).isEqualTo(SessionStatus.OPEN);
        laptop.perform(post("/sessions/{id}/close", id).param("_csrf", formToken))
                .andExpect(redirectedUrl("/sessions/" + id + "/display"));
        assertThat(sessionRepository.findById(id).orElseThrow().getStatus()).isEqualTo(SessionStatus.CLOSED);
    }

    // ------------------------------------------------------------------ public pages

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/login?error", "/privacy", "/css/app.css", "/js/sw-register.js",
            "/js/landing.js", "/manifest.webmanifest", "/sw.js", "/icons/icon-192.png", "/actuator/health"})
    void publicPagesCreateNoSession(String path) throws Exception {
        expectNoSession(anonymous(get(URI.create(path))).andExpect(status().isOk()));
    }

    @Test
    void notFoundAndErrorPagesCreateNoSession() throws Exception {
        expectNoSession(anonymous(get("/favicon.ico")).andExpect(status().isNotFound()));
        expectNoSession(anonymous(get("/css/missing.css")).andExpect(status().isNotFound()));
        // MockMvc doesn't forward to /error by itself; render the error page as the container's error dispatch would.
        expectNoSession(anonymous(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/css/missing.css"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error")));
    }

    @Test
    void aScanThatIsRejectedCreatesNoSession() throws Exception {
        Fixture f = openFixture();
        expectNoSession(anonymous(get("/a/{id}", UUID.randomUUID()).param("t", "whatever"))
                .andExpect(status().isNotFound()));
        expectNoSession(anonymous(get("/a/{id}", f.sessionId()).param("t", "not-a-valid-token"))
                .andExpect(status().isGone()));
        expectNoSession(anonymous(get("/a/{id}", f.sessionId())).andExpect(status().isGone()));
    }

    // ------------------------------------------------------------------ sessions that are legitimate

    @Test
    void aValidScanStillCreatesTheSessionWithThePendingCheckIn() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        assertThat(phone.pending()).isNotNull();
        assertThat(redis.keys(SESSION_KEYS)).hasSize(1);
        assertThat(phone.storedSession().getAttributeNames()).doesNotContain(SAVED_REQUEST);
    }

    @Test
    void signInWithGoogleCreatesTheSessionTheAuthorizationRequestNeeds() throws Exception {
        Browser browser = newBrowser();
        browser.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("https://accounts.google.com/")));
        assertThat(browser.sessionId()).isNotNull();
        assertThat(redis.keys(SESSION_KEYS)).hasSize(1);
    }

    @Test
    void aSignedOutTeacherOpeningTheCoursesPageGetsBackThereAfterSignIn() throws Exception {
        Fixture f = openFixture();
        String email = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        Browser laptop = newBrowser();

        laptop.perform(get("/teacher/courses").header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        assertThat(laptop.sessionId()).as("the deep link was saved in a session").isNotNull();
        assertThat(redis.opsForHash().hasKey(SessionConfig.NAMESPACE + ":sessions:" + laptop.sessionId(),
                SAVED_REQUEST_FIELD)).isTrue();

        String target = signInWithGoogle(laptop, email)
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(URI.create(target).getPath()).isEqualTo("/teacher/courses");
        laptop.perform(get(URI.create(target)))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/courses"));
    }

    @Test
    void aSignedOutTeacherOpeningTheProjectorPageGetsBackThereAfterSignIn() throws Exception {
        // Not the role's home page, so this shows the saved request (not the default target) is used.
        Fixture f = openFixture();
        String email = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        Browser laptop = newBrowser();

        laptop.perform(get("/sessions/{id}/display", f.sessionId()).header(HttpHeaders.ACCEPT, BROWSER_ACCEPT))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));

        String target = signInWithGoogle(laptop, email)
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(URI.create(target).getPath()).isEqualTo("/sessions/" + f.sessionId() + "/display");
        laptop.perform(get(URI.create(target)))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/display"));
    }

    @Test
    void signingInAfterABotProbeLandsOnTheRoleHomePage() throws Exception {
        Fixture f = openFixture();
        String email = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        Browser laptop = newBrowser();
        expectNoSession(laptop.perform(get("/wp-login.php").header(HttpHeaders.ACCEPT, BROWSER_ACCEPT)));

        signInWithGoogle(laptop, email).andExpect(redirectedUrl("/teacher/courses"));
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder checkIn(UUID sessionId) {
        return post("/a/{id}/check-in", sessionId).param("firstName", "Ana").param("lastName", "Popescu");
    }

    /** What display.js's fetch calls send besides the token. */
    private static MockHttpServletRequestBuilder xhr(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.ACCEPT, "application/json").header("X-Requested-With", "XMLHttpRequest");
    }

    private static String token(Pattern pattern, MvcResult page) throws Exception {
        Matcher matcher = pattern.matcher(page.getResponse().getContentAsString());
        assertThat(matcher.find()).as("%s in the page", pattern).isTrue();
        return matcher.group(1);
    }

    private ResultActions anonymous(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header(HttpHeaders.ACCEPT, BROWSER_ACCEPT));
    }

    private void expectNoSession(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        MockHttpServletResponse response = result.getResponse();
        String request = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).as("Set-Cookie of %s", request)
                .noneMatch(cookie -> cookie.startsWith(SESSION_COOKIE + "="));
        assertThat(response.getCookie(SESSION_COOKIE)).as("SESSION cookie of %s", request).isNull();
        assertThat(redis.keys(SESSION_KEYS)).as("session keys in Redis after %s", request).isEmpty();
    }
}
