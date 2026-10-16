package io.github.sanducc.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.sanducc.attendance.common.ClientContext;
import io.github.sanducc.attendance.common.DeviceIdFilter;
import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.common.RateLimiter;
import io.github.sanducc.attendance.common.TrustedProxyForwardedHeaderFilter;
import io.github.sanducc.attendance.support.MutableClock;
import io.github.sanducc.attendance.support.TestProperties;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.header.writers.HstsHeaderWriter;

/**
 * Issue #39 on a request shaped like Cloud Run's: the client IP is the entry Google's front end appended, whatever the
 * client put in {@code X-Forwarded-For} or {@code Forwarded}, while scheme and host (OAuth redirect URI, HTTPS, HSTS)
 * still come from the forwarded headers exactly as with Spring's own filter. The end-to-end variant through the real
 * application context is {@code ForwardedHeadersIntegrationTest} (Testcontainers).
 */
class TrustedProxyForwardedHeaderFilterTest {

    private static final String HOST = "qr-attendance-1057355191791.europe-west1.run.app";
    private static final String PROXY_PEER = "169.254.169.126";
    private static final String CLIENT = "198.51.100.7";

    private final TrustedProxyForwardedHeaderFilter filter = new TrustedProxyForwardedHeaderFilter(1);

    // ---------------------------------------------------------------- client IP

    @Test
    void aSpoofedXForwardedForGivesTheRealClient() throws Exception {
        HttpServletRequest seen = filtered(cloudRunRequest("/a/x", "1.2.3.4", null));

        assertThat(seen.getRemoteAddr()).isEqualTo(CLIENT);
        assertThat(seen.getRemoteHost()).isEqualTo(CLIENT);
    }

    @Test
    void aSpoofedForwardedHeaderGivesTheRealClient() throws Exception {
        // Spring's own filter would take "for=1.2.3.4": it reads the left-most Forwarded element first.
        HttpServletRequest seen = filtered(cloudRunRequest("/a/x", "203.0.113.9", "for=1.2.3.4"));

        assertThat(seen.getRemoteAddr()).isEqualTo(CLIENT);
    }

    @Test
    void anHonestClientGetsItsOwnAddress() throws Exception {
        assertThat(filtered(cloudRunRequest("/a/x", null, null)).getRemoteAddr()).isEqualTo(CLIENT);
    }

    @Test
    void withoutForwardedHeadersTheRequestIsUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x");
        request.setRemoteAddr("192.0.2.10");

        HttpServletRequest seen = filtered(request);

        assertThat(seen).isSameAs(request);
        assertThat(seen.getRemoteAddr()).isEqualTo("192.0.2.10");
        assertThat(seen.isSecure()).isFalse();
    }

    @Test
    void twoTrustedHops() throws Exception {
        MockHttpServletRequest request = cloudRunRequest("/a/x", null, null);
        request.removeHeader("X-Forwarded-For");
        request.addHeader("X-Forwarded-For", "1.2.3.4, " + CLIENT + ", 34.111.22.33");

        assertThat(filtered(new TrustedProxyForwardedHeaderFilter(2), request).getRemoteAddr()).isEqualTo(CLIENT);
        assertThat(filtered(new TrustedProxyForwardedHeaderFilter(0), cloudRunRequest("/a/x", "1.2.3.4", null))
                .getRemoteAddr()).isEqualTo(PROXY_PEER);
    }

    @Test
    void negativeHopsAreRejected() {
        assertThatThrownBy(() -> new TrustedProxyForwardedHeaderFilter(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCheckInFlowSeesTheRealClient() throws Exception {
        MockHttpServletRequest request = cloudRunRequest("/a/x", "1.2.3.4", "for=1.2.3.4");
        request.setAttribute(DeviceIdFilter.REQUEST_ATTRIBUTE, "device-1");

        assertThat(ClientContext.from(filtered(request)).ip()).isEqualTo(CLIENT);
    }

    // ---------------------------------------------------------------- scheme and host stay as before

    @Test
    void schemeHostAndPortStillComeFromTheProxy() throws Exception {
        HttpServletRequest seen = filtered(cloudRunRequest("/sessions/x/display", "1.2.3.4", "for=1.2.3.4"));

        assertThat(seen.getScheme()).isEqualTo("https");
        assertThat(seen.isSecure()).isTrue();
        assertThat(seen.getServerName()).isEqualTo(HOST);
        assertThat(seen.getServerPort()).isEqualTo(443);
        assertThat(seen.getRequestURL()).hasToString("https://" + HOST + "/sessions/x/display");
        assertThat(seen.getHeader("X-Forwarded-For")).as("hidden from the app, as before").isNull();
        assertThat(seen.getHeader("X-Forwarded-Proto")).isNull();
        assertThat(seen.getHeader("Forwarded")).isNull();
    }

    @Test
    void theOAuthRedirectUriIsTheHttpsRunAppUrl() throws Exception {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("client-id").clientSecret("client-secret").build();
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(google), "/oauth2/authorization");

        HttpServletRequest seen = filtered(cloudRunRequest("/oauth2/authorization/google", "1.2.3.4", null));
        OAuth2AuthorizationRequest authorization = resolver.resolve(seen);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getRedirectUri()).isEqualTo("https://" + HOST + "/login/oauth2/code/google");
    }

    @Test
    void hstsIsSentBecauseTheRequestIsSecure() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new HstsHeaderWriter().writeHeaders(filtered(cloudRunRequest("/", "1.2.3.4", null)), response);
        assertThat(response.getHeader("Strict-Transport-Security")).startsWith("max-age=");

        MockHttpServletResponse plain = new MockHttpServletResponse();
        new HstsHeaderWriter().writeHeaders(filtered(new MockHttpServletRequest("GET", "/")), plain);
        assertThat(plain.getHeader("Strict-Transport-Security")).isNull();
    }

    // ---------------------------------------------------------------- the per-IP rate limit keys on the client

    @Test
    void rotatingSpoofedHeadersDoNotDodgeTheJobEndpointsPerIpLimit() throws Exception {
        Map<String, Long> counters = new HashMap<>();
        PepperedHasher hasher = new PepperedHasher(TestProperties.defaults());
        JobTriggerSecurityConfig.PerIpRateLimitFilter perIp =
                new JobTriggerSecurityConfig.PerIpRateLimitFilter(rateLimiter(counters), hasher);

        for (int i = 1; i <= JobTriggerSecurityConfig.PER_IP_PER_MINUTE; i++) {
            MockHttpServletRequest spoofed = cloudRunRequest(JobTriggerSecurityConfig.DAILY_JOBS_PATH,
                    "203.0.113." + i, "for=203.0.113." + i);
            assertThat(callJobEndpoint(perIp, spoofed)).as("call %d", i).isEqualTo(401);
        }
        assertThat(callJobEndpoint(perIp, cloudRunRequest(JobTriggerSecurityConfig.DAILY_JOBS_PATH,
                "203.0.113.200", "for=203.0.113.200"))).isEqualTo(429);

        assertThat(counters).hasSize(1);
        assertThat(counters.keySet().iterator().next()).contains("job:ip:" + hasher.hash("ip", CLIENT));

        MockHttpServletRequest otherClient = cloudRunRequest(JobTriggerSecurityConfig.DAILY_JOBS_PATH, null, null);
        otherClient.removeHeader("X-Forwarded-For");
        otherClient.addHeader("X-Forwarded-For", "198.51.100.8");
        assertThat(callJobEndpoint(perIp, otherClient)).as("another client has its own budget").isEqualTo(401);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A request as the container gets it from Cloud Run: plain HTTP from Google's proxy, the public host, and
     * forwarded headers where the proxy's own entries come after whatever the client sent.
     */
    private static MockHttpServletRequest cloudRunRequest(String path, String clientXForwardedFor,
                                                          String clientForwarded) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setScheme("http");
        request.setServerName(HOST);
        request.setServerPort(8080);
        request.setRemoteAddr(PROXY_PEER);
        request.addHeader(HttpHeaders.HOST, HOST);
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-For",
                clientXForwardedFor == null ? CLIENT : clientXForwardedFor + "," + CLIENT);
        request.addHeader("Forwarded", (clientForwarded == null ? "" : clientForwarded + ", ")
                + "for=\"" + CLIENT + "\";proto=https");
        return request;
    }

    private HttpServletRequest filtered(MockHttpServletRequest request) throws Exception {
        return filtered(filter, request);
    }

    private static HttpServletRequest filtered(TrustedProxyForwardedHeaderFilter filter,
                                               MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    /** Forwarded-header filter, then the job endpoint's per-IP limit, then a stand-in for the token check (401). */
    private int callJobEndpoint(JobTriggerSecurityConfig.PerIpRateLimitFilter perIp, MockHttpServletRequest request)
            throws Exception {
        HttpServlet tokenCheck = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(401);
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(tokenCheck, filter, perIp).doFilter(request, response);
        return response.getStatus();
    }

    @SuppressWarnings("unchecked")
    private static RateLimiter rateLimiter(Map<String, Long> counters) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        return new RateLimiter(redis, new MutableClock());
    }
}
