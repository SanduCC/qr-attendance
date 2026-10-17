package io.github.sanducc.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpSession;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * Where the CSRF token is stored (issue #47): never in a session created by a state-changing request, otherwise as
 * {@link HttpSessionCsrfTokenRepository}. Plain unit test, with Spring Security's own {@link CsrfFilter} for the
 * accept/reject decisions; the end-to-end check with Redis is {@code AnonymousRequestSessionTest}.
 */
class SessionOnlyCsrfTokenRepositoryTest {

    /** HttpSessionCsrfTokenRepository's default session attribute. */
    private static final String TOKEN_ATTRIBUTE = HttpSessionCsrfTokenRepository.class.getName() + ".CSRF_TOKEN";

    private final SessionOnlyCsrfTokenRepository repository = new SessionOnlyCsrfTokenRepository();

    // ------------------------------------------------------------------ storing

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void aStateChangingRequestWithoutASessionStoresNothing(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/xmlrpc.php");
        repository.saveToken(repository.generateToken(request), request, new MockHttpServletResponse());
        assertThat(request.getSession(false)).isNull();
        assertThat(repository.loadToken(request)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "GET"})
    void aRequestWithASessionStoresTheTokenThere(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/a/x/check-in");
        HttpSession session = request.getSession(true);
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, new MockHttpServletResponse());
        assertThat(session.getAttribute(TOKEN_ATTRIBUTE)).isEqualTo(token);
        assertThat(repository.loadToken(request)).isEqualTo(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
    void aSafeRequestRenderingATokenStillCreatesTheSession(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/a/x/confirm");
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, new MockHttpServletResponse());
        assertThat(request.getSession(false)).isNotNull();
        assertThat(repository.loadToken(request)).isEqualTo(token);
    }

    @Test
    void removingAndLoadingNeverCreateASession() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        repository.saveToken(null, request, new MockHttpServletResponse());
        assertThat(repository.loadToken(request)).isNull();
        assertThat(request.getSession(false)).isNull();

        MockHttpServletRequest signedIn = new MockHttpServletRequest("POST", "/logout");
        signedIn.getSession(true).setAttribute(TOKEN_ATTRIBUTE, repository.generateToken(signedIn));
        repository.saveToken(null, signedIn, new MockHttpServletResponse());
        assertThat(signedIn.getSession(false).getAttribute(TOKEN_ATTRIBUTE)).isNull();
    }

    // ------------------------------------------------------------------ with CsrfFilter

    @Test
    void anAnonymousPostIsRejectedWithoutASession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/xmlrpc.php");
        request.setParameter("_csrf", "bWFkZS11cA");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(passes(request, response)).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void aPostWithTheTokenItsSessionWasGivenPassesAndAnyOtherIsRejected() throws Exception {
        // A page rendered on GET: the token the form carries, stored in the (new) session.
        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/a/x/confirm");
        assertThat(passes(page, new MockHttpServletResponse())).isTrue();
        String rendered = ((CsrfToken) page.getAttribute(CsrfToken.class.getName())).getToken();
        HttpSession session = page.getSession(false);
        assertThat(session).isNotNull();

        MockHttpServletRequest withToken = post(session, rendered);
        assertThat(passes(withToken, new MockHttpServletResponse())).isTrue();

        MockHttpServletResponse withoutToken = new MockHttpServletResponse();
        assertThat(passes(post(session, null), withoutToken)).isFalse();
        assertThat(withoutToken.getStatus()).isEqualTo(403);

        // Another session's token: real, but not this session's.
        MockHttpServletRequest otherPage = new MockHttpServletRequest("GET", "/a/x/confirm");
        passes(otherPage, new MockHttpServletResponse());
        String othersToken = ((CsrfToken) otherPage.getAttribute(CsrfToken.class.getName())).getToken();
        MockHttpServletResponse withOthersToken = new MockHttpServletResponse();
        assertThat(passes(post(session, othersToken), withOthersToken)).isFalse();
        assertThat(withOthersToken.getStatus()).isEqualTo(403);

        // The refusals didn't replace the session's token.
        assertThat(passes(post(session, rendered), new MockHttpServletResponse())).isTrue();
    }

    private static MockHttpServletRequest post(HttpSession session, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a/x/check-in");
        request.setSession(session);
        if (token != null) {
            request.setParameter("_csrf", token);
        }
        return request;
    }

    /** Runs Spring Security's CsrfFilter (default, BREACH-safe token handler) with this repository. */
    private boolean passes(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        new CsrfFilter(repository).doFilter(request, response, (req, res) -> {
            passed.set(true);
            // What a page with a form does: read the token, so a missing one is generated and stored.
            ((CsrfToken) req.getAttribute(CsrfToken.class.getName())).getToken();
        });
        return passed.get();
    }
}
