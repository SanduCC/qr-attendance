package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.support.TestProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Anti-cheat rule 4: a long-lived, signed, httpOnly device cookie on every student page. */
class DeviceIdFilterTest {

    private static final long MAX_AGE_SECONDS = Duration.ofDays(400).toSeconds();

    private final AttendanceProperties properties = TestProperties.defaults();
    private final DeviceIdFilter filter = new DeviceIdFilter(new PepperedHasher(properties), properties);

    @Test
    void issuesASecureHttpOnlyLaxCookieOnFirstVisit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/some-session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("chain continues").isSameAs(request);
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).startsWith(DeviceIdFilter.COOKIE_NAME + "=")
                .contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/", "Max-Age=" + MAX_AGE_SECONDS);

        MockCookie cookie = (MockCookie) response.getCookie(DeviceIdFilter.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo((int) MAX_AGE_SECONDS);
        assertThat(cookie.getPath()).isEqualTo("/");

        String deviceId = DeviceIdFilter.deviceId(request);
        assertThat(deviceId).isNotBlank();
        assertThat(cookie.getValue()).startsWith(deviceId + ".");
        assertThat(filter.decode(cookie.getValue())).isEqualTo(deviceId);
    }

    @Test
    void omitsSecureWhenSecureCookiesAreDisabled() throws Exception {
        AttendanceProperties insecure = TestProperties.with(TestProperties.QR_SECRET, TestProperties.PEPPER, false);
        DeviceIdFilter devFilter = new DeviceIdFilter(new PepperedHasher(insecure), insecure);
        MockHttpServletResponse response = new MockHttpServletResponse();

        devFilter.doFilter(new MockHttpServletRequest("GET", "/a/x"), response, new MockFilterChain());

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).contains("HttpOnly", "SameSite=Lax").doesNotContain("Secure");
    }

    @Test
    void eachNewBrowserGetsADifferentDeviceId() throws Exception {
        assertThat(firstVisitDeviceId()).isNotEqualTo(firstVisitDeviceId());
    }

    @Test
    void keepsAValidlySignedCookie() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/a/x");
        filter.doFilter(firstRequest, first, new MockFilterChain());
        Cookie issued = first.getCookie(DeviceIdFilter.COOKIE_NAME);

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/a/x/check-in");
        second.setCookies(new Cookie(DeviceIdFilter.COOKIE_NAME, issued.getValue()));
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());

        assertThat(secondResponse.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(DeviceIdFilter.deviceId(second)).isEqualTo(DeviceIdFilter.deviceId(firstRequest));
    }

    @Test
    void replacesAForgedCookie() throws Exception {
        assertReplaced("my-chosen-id.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "my-chosen-id");
    }

    @Test
    void replacesAnUnsignedCookie() throws Exception {
        assertReplaced("my-chosen-id", "my-chosen-id");
    }

    @Test
    void replacesACookieWithABrokenSignature() throws Exception {
        assertReplaced("my-chosen-id.!!!not-base64!!!", "my-chosen-id");
        assertReplaced(".signature-without-id", "");
    }

    @Test
    void replacesACookieSignedWithAnotherPepper() throws Exception {
        AttendanceProperties other = TestProperties.with(TestProperties.QR_SECRET,
                "a-completely-different-pepper-value-123456", true);
        DeviceIdFilter otherFilter = new DeviceIdFilter(new PepperedHasher(other), other);
        String foreign = otherFilter.encode("victim-device");

        assertReplaced(foreign, "victim-device");
    }

    @Test
    void replacesAnOverlongCookie() throws Exception {
        String longId = "x".repeat(200);
        assertReplaced(filter.encode(longId), longId);
    }

    @Test
    void skipsNonStudentPaths() throws Exception {
        for (String path : new String[] {"/teacher/courses", "/sessions/x/display", "/", "/login", "/abc/a/x"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(chain.getRequest()).as(path).isSameAs(request);
            assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).as(path).isNull();
            assertThat(DeviceIdFilter.deviceId(request)).as(path).isNull();
        }
    }

    @Test
    void honoursTheContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/a/x");
        request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(DeviceIdFilter.deviceId(request)).isNotBlank();
    }

    private String firstVisitDeviceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return DeviceIdFilter.deviceId(request);
    }

    private void assertReplaced(String presentedValue, String attackerChosenId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/a/x");
        request.setCookies(new Cookie(DeviceIdFilter.COOKIE_NAME, presentedValue));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Cookie replacement = response.getCookie(DeviceIdFilter.COOKIE_NAME);
        assertThat(replacement).as("a new cookie replaces " + presentedValue).isNotNull();
        assertThat(replacement.getValue()).isNotEqualTo(presentedValue);
        String deviceId = DeviceIdFilter.deviceId(request);
        assertThat(deviceId).isNotBlank().isNotEqualTo(attackerChosenId);
        assertThat(filter.decode(replacement.getValue())).isEqualTo(deviceId);
    }
}
