package io.github.sanducc.attendance.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The session-backed CSRF token store Spring Security uses by default ({@link HttpSessionCsrfTokenRepository}),
 * except that a request that needs CSRF protection never <em>creates</em> an HTTP session just to store a token
 * (issue #47).
 *
 * <p>Why: on a POST (PUT, DELETE, PATCH) {@link CsrfFilter} loads the session's token, finds none on a request
 * without a session, generates a fresh one and saves it, and only then compares it with the submitted value and
 * rejects the request with 403. Saving it created a Redis session and a {@code SESSION} cookie for every bot
 * hitting {@code POST /xmlrpc.php}. That token is never usable: it was generated after the request was sent, so
 * the request carries no copy of it and fails the check, and the 403 response is the only place it would reach.
 *
 * <p>What changes and what doesn't:
 * <ul>
 *   <li>A CSRF-protected request with no session: the fresh token is not stored. The check runs against it all the
 *       same, so the request is rejected exactly as before ({@code MissingCsrfTokenException}, 403).</li>
 *   <li>A request with a session: unchanged. Its token is loaded from, and a missing one saved to, the session.</li>
 *   <li>A safe request (GET, HEAD, TRACE, OPTIONS) with no session that renders a token, e.g. a page with a form:
 *       unchanged, the session is created so the token survives until the form is posted. Today every such page
 *       is a signed-in one (which already has a session).</li>
 *   <li>Removing the token ({@code saveToken(null)}, on sign-in and logout) and loading it never create a session,
 *       as in the delegate.</li>
 * </ul>
 * Accepted requests are exactly the same set as before: a protected request is accepted only if it carries the
 * token stored in its own session. The token handler is untouched (Spring Security's BREACH-safe
 * {@code XorCsrfTokenRequestAttributeHandler}).
 */
final class SessionOnlyCsrfTokenRepository implements CsrfTokenRepository {

    /** The requests {@link CsrfFilter} checks; the browser chain uses this default. */
    private static final RequestMatcher REQUIRES_CSRF_PROTECTION = CsrfFilter.DEFAULT_CSRF_MATCHER;

    private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (token != null && request.getSession(false) == null && REQUIRES_CSRF_PROTECTION.matches(request)) {
            // A state-changing request without a session: it is about to be rejected, keep it stateless.
            return;
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
