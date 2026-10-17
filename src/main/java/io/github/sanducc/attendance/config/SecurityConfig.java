package io.github.sanducc.attendance.config;

import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginEmbedderPolicyHeaderWriter.CrossOriginEmbedderPolicy;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.RequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;

@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "img-src 'self' data:",
            "style-src 'self'",
            "script-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    /**
     * Browser features no page uses. The clipboard (landing page "copy link") is left alone. The geofence (#3)
     * will need {@code geolocation=(self)}.
     */
    static final String PERMISSIONS_POLICY = String.join(", ",
            "accelerometer=()", "browsing-topics=()", "camera=()", "display-capture=()", "geolocation=()",
            "gyroscope=()", "magnetometer=()", "microphone=()", "midi=()", "payment=()", "usb=()");

    /**
     * The pre-login request a signed-out visitor is sent back to after signing in (issue #44). Saving it creates an
     * HTTP session, i.e. a Redis write and a {@code SESSION} cookie, so only the app's own signed-in pages qualify:
     * GET under the teacher, admin, session and student controllers ({@code /teacher/courses/**},
     * {@code /admin/teachers/**}, {@code /sessions/{id}/**}, {@code /a/{id}/**}, e.g. a student reopening their
     * "done" page after the session expired). The patterns name the controllers' prefixes, not just
     * {@code /admin/**}, because bots probe {@code /admin}, {@code /admin/login.php} and the like. Every other
     * anonymous request ({@code /wp-login.php}, {@code /.env}, ...) is still redirected to {@code /login}, but
     * statelessly. Spring's default exclusions stay: XHR, JSON, multipart, event-stream and favicon requests are
     * never saved.
     *
     * <p>The scan flow doesn't depend on this: {@code GET /a/{id}?t=} is public and stores its own
     * {@code PendingCheckIn}, which {@link LoginSuccessHandler} checks first. The OAuth2 login itself
     * ({@code /oauth2/authorization/google}) still creates the session its authorization request needs.
     */
    @Bean
    RequestCache requestCache() {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher deepLink = new OrRequestMatcher(
                paths.matcher(HttpMethod.GET, "/teacher/courses/**"),
                paths.matcher(HttpMethod.GET, "/admin/teachers/**"),
                paths.matcher(HttpMethod.GET, "/sessions/*/**"),
                paths.matcher(HttpMethod.GET, "/a/*/**"));
        // The exclusions Spring Security's RequestCacheConfigurer gives its default cache (a custom cache replaces
        // that default, matcher included).
        RequestMatcher springDefaults = new AndRequestMatcher(
                new NegatedRequestMatcher(paths.matcher("/favicon.*")),
                notAccepting(MediaType.APPLICATION_JSON),
                new NegatedRequestMatcher(new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest")),
                notAccepting(MediaType.MULTIPART_FORM_DATA),
                notAccepting(MediaType.TEXT_EVENT_STREAM));
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(new AndRequestMatcher(deepLink, springDefaults));
        return cache;
    }

    private static RequestMatcher notAccepting(MediaType mediaType) {
        MediaTypeRequestMatcher accepts =
                new MediaTypeRequestMatcher(new HeaderContentNegotiationStrategy(), mediaType);
        accepts.setIgnoredMediaTypes(Set.of(MediaType.ALL));
        return new NegatedRequestMatcher(accepts);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AppOidcUserService oidcUserService,
                                            LoginSuccessHandler loginSuccessHandler,
                                            RequestCache requestCache) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/privacy", "/error",
                                "/css/**", "/js/**", "/icons/**",
                                "/manifest.webmanifest", "/sw.js", "/favicon.ico", "/robots.txt",
                                "/actuator/health").permitAll()
                        // Scan landing is public: the QR token is validated before sign-in.
                        .requestMatchers(HttpMethod.GET, "/a/*").permitAll()
                        .requestMatchers("/a/**").authenticated()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/teacher/**", "/sessions/**").hasRole("TEACHER")
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.requestCache(requestCache))
                .oauth2Login(oauth -> oauth
                        .loginPage("/login")
                        .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                        .successHandler(loginSuccessHandler))
                .logout(logout -> logout.logoutSuccessUrl("/"))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        // The scan URL carries the QR token; don't leak it to Google via Referer.
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN))
                        .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY))
                        // Cross-origin isolation (Spectre). Every page loads only same-origin resources (CSP 'self')
                        // and Google sign-in is a top-level redirect, not a popup, so the strict values break nothing.
                        .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .crossOriginEmbedderPolicy(coep -> coep.policy(CrossOriginEmbedderPolicy.REQUIRE_CORP))
                        .crossOriginResourcePolicy(corp -> corp.policy(CrossOriginResourcePolicy.SAME_ORIGIN)));
        return http.build();
    }
}
