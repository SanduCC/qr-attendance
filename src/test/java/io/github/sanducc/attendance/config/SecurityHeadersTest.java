package io.github.sanducc.attendance.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The security headers are on every kind of response the public can reach: pages, static assets, the scan landing's
 * error page, redirects to sign-in and 404s. These are what the ZAP baseline scan of production checks
 * (.github/workflows/dast.yml); a header missing here would show up there as a finding.
 *
 * <p>Permissions-Policy turns off every browser feature no page uses, except geolocation, which is
 * {@code geolocation=(self)}: the optional location check (#3, rule 6) reads it on the teacher's start-options form
 * and on the student's confirm page, so those two pages are checked here too.
 */
class SecurityHeadersTest extends IntegrationTestSupport {

    @Test
    void publicPagesCarryAllSecurityHeaders() throws Exception {
        expectSecurityHeaders(get("/")).andExpect(status().isOk());
        expectSecurityHeaders(get("/login")).andExpect(status().isOk());
        expectSecurityHeaders(get("/privacy")).andExpect(status().isOk());
    }

    @Test
    void staticAssetsCarryAllSecurityHeaders() throws Exception {
        expectSecurityHeaders(get("/css/app.css")).andExpect(status().isOk());
        expectSecurityHeaders(get("/js/sw-register.js")).andExpect(status().isOk());
        expectSecurityHeaders(get("/manifest.webmanifest")).andExpect(status().isOk());
        expectSecurityHeaders(get("/icons/icon-192.png")).andExpect(status().isOk());
        expectSecurityHeaders(get("/robots.txt")).andExpect(status().isOk());
    }

    @Test
    void scanLandingErrorPageCarriesAllSecurityHeaders() throws Exception {
        expectSecurityHeaders(get("/a/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void redirectToSignInCarriesAllSecurityHeaders() throws Exception {
        expectSecurityHeaders(get("/teacher/courses")).andExpect(status().is3xxRedirection());
    }

    @Test
    void notFoundForASignedInUserCarriesAllSecurityHeaders() throws Exception {
        expectSecurityHeaders(get("/no-such-page").with(studentLogin("sub-404", "Ana", "Pop")))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePagesThatAskForTheLocationMayUseGeolocation() throws Exception {
        Fixture f = openFixture();
        // The student's confirm page (after a scan) and the teacher's course page with the start options.
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        phone.perform(get("/a/{id}/confirm", f.sessionId()).with(studentLogin("sub-geo", "Ana", "Pop")))
                .andExpect(status().isOk())
                .andExpectAll(securityHeaders());
        String teacher = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        expectSecurityHeaders(get("/teacher/courses/{id}", f.courseId()).with(teacherLogin(teacher)))
                .andExpect(status().isOk());
        expectSecurityHeaders(get("/teacher/courses").with(teacherLogin(teacher))).andExpect(status().isOk());
    }

    private ResultActions expectSecurityHeaders(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andExpectAll(securityHeaders());
    }

    private static ResultMatcher[] securityHeaders() {
        return new ResultMatcher[] {
                header().string("Content-Security-Policy", containsString("default-src 'self'")),
                header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")),
                header().string("X-Content-Type-Options", "nosniff"),
                header().string("X-Frame-Options", "DENY"),
                header().string("Referrer-Policy", "same-origin"),
                header().string("Permissions-Policy", SecurityConfig.PERMISSIONS_POLICY),
                header().string("Permissions-Policy", containsString("camera=()")),
                // The location check (#3) needs geolocation, for our own origin only.
                header().string("Permissions-Policy", containsString("geolocation=(self)")),
                header().string("Permissions-Policy", not(containsString("geolocation=()"))),
                header().string("Cross-Origin-Opener-Policy", "same-origin"),
                header().string("Cross-Origin-Embedder-Policy", "require-corp"),
                header().string("Cross-Origin-Resource-Policy", "same-origin"),
        };
    }
}
