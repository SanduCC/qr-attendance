package io.github.sanducc.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.savedrequest.RequestCache;

/**
 * Which pre-login requests are saved (and so create an HTTP session) before the redirect to sign-in (issue #44).
 * Plain unit test of {@link SecurityConfig#requestCache()}; the end-to-end check with Redis is
 * {@code AnonymousRequestSessionTest}.
 */
class SavedRequestPolicyTest {

    private final RequestCache cache = new SecurityConfig().requestCache();

    @ParameterizedTest
    @ValueSource(strings = {
            "/teacher/courses",
            "/teacher/courses/42",
            "/admin/teachers",
            "/sessions/5b7c8f0e-0000-4000-8000-000000000000/display",
            "/sessions/5b7c8f0e-0000-4000-8000-000000000000/export.csv",
            "/a/5b7c8f0e-0000-4000-8000-000000000000/done",
            "/a/5b7c8f0e-0000-4000-8000-000000000000/confirm"})
    void deepLinksAreSaved(String path) {
        MockHttpServletRequest request = browserGet(path);
        cache.saveRequest(request, new MockHttpServletResponse());
        assertThat(request.getSession(false)).as("saved %s", path).isNotNull();
        assertThat(cache.getRequest(request, new MockHttpServletResponse()).getRedirectUrl())
                .contains(path);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/robots.txt", "/sitemap.xml", "/wp-login.php", "/.env", "/nonexistent-page", "/a", "/teacher",
            "/admin", "/admin/", "/admin/login.php", "/sessions", "/favicon.png", "/xmlrpc.php"})
    void otherPathsAreNotSaved(String path) {
        assertNotSaved(browserGet(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "HEAD"})
    void onlyGetIsSaved(String method) {
        MockHttpServletRequest request = browserGet("/teacher/courses");
        request.setMethod(method);
        assertNotSaved(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/event-stream", "multipart/form-data"})
    void apiAndStreamRequestsAreNotSaved(String accept) {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/sessions/5b7c8f0e-0000-4000-8000-000000000000/records");
        request.addHeader("Accept", accept);
        assertNotSaved(request);
    }

    @Test
    void xhrIsNotSaved() {
        MockHttpServletRequest request = browserGet("/sessions/5b7c8f0e-0000-4000-8000-000000000000/records");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        assertNotSaved(request);
    }

    private void assertNotSaved(MockHttpServletRequest request) {
        cache.saveRequest(request, new MockHttpServletResponse());
        assertThat(request.getSession(false)).as("%s %s created a session", request.getMethod(),
                request.getRequestURI()).isNull();
    }

    private static MockHttpServletRequest browserGet(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return request;
    }
}
