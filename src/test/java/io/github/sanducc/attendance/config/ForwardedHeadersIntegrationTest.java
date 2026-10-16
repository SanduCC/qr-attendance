package io.github.sanducc.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanducc.attendance.checkin.AttendanceRecord;
import io.github.sanducc.attendance.common.DeviceIdFilter;
import io.github.sanducc.attendance.common.TrustedProxyForwardedHeaderFilter;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Issue #39 through the real application with {@code server.forward-headers-strategy=framework}, as on Cloud Run:
 * the IP the per-IP rate limits key on and {@code ip_hash} stores is the one Google's front end appended, not one the
 * client sent, and the OAuth redirect URI, HTTPS detection, HSTS and secure cookies still work behind the proxy.
 */
@TestPropertySource(properties = "server.forward-headers-strategy=framework")
class ForwardedHeadersIntegrationTest extends IntegrationTestSupport {

    private static final String HOST = "qr-attendance-1057355191791.europe-west1.run.app";
    private static final String CLIENT = "198.51.100.7";

    @Autowired List<FilterRegistrationBean<?>> filterRegistrations;

    @Test
    void ourFilterReplacesSpringBootsForwardedHeaderFilter() {
        List<?> forwardedFilters = filterRegistrations.stream()
                .map(FilterRegistrationBean::getFilter)
                .filter(ForwardedHeaderFilter.class::isInstance)
                .toList();
        assertThat(forwardedFilters).hasSize(1);
        assertThat(forwardedFilters.getFirst()).isInstanceOf(TrustedProxyForwardedHeaderFilter.class);
    }

    @Test
    void theOAuthRedirectUriIsHttpsOnThePublicHost() throws Exception {
        String location = mvc.perform(behindCloudRun(get("/oauth2/authorization/google"), "1.2.3.4"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().exists("Strict-Transport-Security"))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8))
                .startsWith("https://accounts.google.com/")
                .contains("redirect_uri=https://" + HOST + "/login/oauth2/code/google");
    }

    @Test
    void withoutTheProxyHeadersTheRequestIsPlainHttp() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().doesNotExist("Strict-Transport-Security"))
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(URLDecoder.decode(location, StandardCharsets.UTF_8))
                .contains("redirect_uri=http://localhost/login/oauth2/code/google");
    }

    @Test
    void rotatingSpoofedHeadersDoNotDodgeTheJobEndpointsPerIpLimit() throws Exception {
        for (int i = 1; i <= JobTriggerSecurityConfig.PER_IP_PER_MINUTE; i++) {
            mvc.perform(behindCloudRun(post(JobTriggerSecurityConfig.DAILY_JOBS_PATH), "203.0.113." + i)
                            .header("Forwarded", "for=203.0.113." + i))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(behindCloudRun(post(JobTriggerSecurityConfig.DAILY_JOBS_PATH), "203.0.113.200")
                        .header("Forwarded", "for=203.0.113.200"))
                .andExpect(status().isTooManyRequests());

        // Another client behind the same proxy has its own budget.
        mvc.perform(post(JobTriggerSecurityConfig.DAILY_JOBS_PATH)
                        .header(HttpHeaders.HOST, HOST)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-For", "198.51.100.8"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCheckInWithASpoofedHeaderStoresTheRealClientsIpHash() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        phone.perform(behindCloudRun(get("/a/{id}", f.sessionId()).param("t", validToken(f.sessionId())),
                        "1.2.3.4"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    Cookie device = result.getResponse().getCookie(DeviceIdFilter.COOKIE_NAME);
                    assertThat(device).isNotNull();
                    assertThat(device.getSecure()).isTrue();
                });
        phone.perform(behindCloudRun(post("/a/{id}/check-in", f.sessionId()), "1.2.3.4")
                        .with(csrf()).with(studentLogin("sub-1", "Ana", "Pop"))
                        .param("firstName", "Ana").param("lastName", "Pop"))
                .andExpect(status().is3xxRedirection());

        List<AttendanceRecord> records = recordRepository.findAll();
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getIpHash())
                .isEqualTo(hasher.hash("ip", CLIENT))
                .isNotEqualTo(hasher.hash("ip", "1.2.3.4"))
                .isNotEqualTo(hasher.hash("ip", RAW_TEST_IP));
    }

    /** What Cloud Run adds: the public host, https, and the client's address after whatever the client sent. */
    private static MockHttpServletRequestBuilder behindCloudRun(MockHttpServletRequestBuilder request,
                                                                String spoofedXForwardedFor) {
        return request
                .header(HttpHeaders.HOST, HOST)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-For", spoofedXForwardedFor + ", " + CLIENT);
    }
}
