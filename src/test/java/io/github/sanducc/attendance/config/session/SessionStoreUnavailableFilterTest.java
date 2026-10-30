package io.github.sanducc.attendance.config.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Redis down: a clean 503 "try again" page, not a 500, and unrelated errors pass through untouched. */
class SessionStoreUnavailableFilterTest {

    private final SessionStoreUnavailableFilter filter = new SessionStoreUnavailableFilter();

    @Test
    void sessionStoreFailureDeepInTheCauseChainBecomesA503Page() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("half a page");
        FilterChain chain = (req, res) -> {
            throw new ServletException("Request processing failed",
                    new SessionStoreUnavailableException("HTTP session store (Redis) unavailable",
                            new RedisConnectionFailureException("Unable to connect to Redis")));
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/teacher/courses"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(response.getContentAsString()).contains("Temporarily unavailable").doesNotContain("half a page");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theLoggedPathCannotForgeALogLine(CapturedOutput output) throws Exception {
        FilterChain chain = (req, res) -> {
            throw new SessionStoreUnavailableException("HTTP session store (Redis) unavailable",
                    new RedisConnectionFailureException("Unable to connect to Redis"));
        };
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x\r\nINFO Fake entry");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(output.getAll())
                .contains("GET /a/x__INFO Fake entry failed: HTTP session store (Redis) unavailable")
                .doesNotContain("/a/x\r\n").doesNotContain("/a/x\n");
    }

    @Test
    void otherErrorsAreRethrown() {
        IllegalStateException unrelated = new IllegalStateException("boom");
        FilterChain chain = (req, res) -> {
            throw unrelated;
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain))
                .isSameAs(unrelated);
    }

    @Test
    void healthyRequestsPassThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> res.getWriter().write("ok"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("ok");
    }
}
