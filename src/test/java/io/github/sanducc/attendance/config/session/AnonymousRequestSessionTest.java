package io.github.sanducc.attendance.config.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.support.IntegrationTestSupport;
import jakarta.servlet.RequestDispatcher;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Anonymous requests don't create HTTP sessions (issue #44): every session is a Redis write (Upstash's free command
 * budget) and a {@code SESSION} cookie. Only the app's own deep links save the pre-login request, the scan landing
 * stores its pending check-in, and "Sign in with Google" stores the OAuth2 authorization request. Bots probing
 * random paths, and the public pages, leave nothing behind.
 */
class AnonymousRequestSessionTest extends IntegrationTestSupport {

    private static final String SESSION_KEYS = SessionConfig.NAMESPACE + ":*";
    /** {@link org.springframework.security.web.savedrequest.HttpSessionRequestCache}'s default attribute. */
    private static final String SAVED_REQUEST = "SPRING_SECURITY_SAVED_REQUEST";
    private static final String SAVED_REQUEST_FIELD = "sessionAttr:" + SAVED_REQUEST;
    private static final String BROWSER_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

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
