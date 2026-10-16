package io.github.sanducc.attendance.config;

import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.common.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security for the one machine-to-machine endpoint, {@code POST /internal/jobs/daily} (Cloud Scheduler, issue #26).
 * A separate filter chain that matches only that path and runs before the browser chain in {@link SecurityConfig}:
 * <ul>
 *   <li>Bearer token only: a Google-signed OIDC token of the configured service account for the exact audience
 *       ({@link JobTriggerTokens}). No session, no cookies, no login redirect.</li>
 *   <li>CSRF is skipped for this path only. CSRF protects cookie-authenticated browsers; this chain ignores cookies,
 *       so a cross-site form can't carry the credential.</li>
 *   <li>Failures are a bare 401 (no or bad token) or 403 (valid token, wrong method), without a body or a
 *       {@code WWW-Authenticate} error description. The reason goes to the log only.</li>
 *   <li>Rate-limited per (hashed) client IP before the token is even parsed.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class JobTriggerSecurityConfig {

    public static final String DAILY_JOBS_PATH = "/internal/jobs/daily";
    static final String AUTHORITY = "JOB_TRIGGER";
    /** Cloud Scheduler calls once a day (plus retries); this only bounds what anyone else can make us verify. */
    static final int PER_IP_PER_MINUTE = 10;

    private static final Logger log = LoggerFactory.getLogger(JobTriggerSecurityConfig.class);

    /** The audience the token must carry: configured, or {@code PUBLIC_BASE_URL} + the endpoint path. */
    public static String audience(JobProperties jobs, AttendanceProperties attendance) {
        String configured = jobs.trigger().audience();
        return configured != null ? configured : attendance.publicBaseUrl() + DAILY_JOBS_PATH;
    }

    @Bean
    JwtDecoder jobTriggerJwtDecoder(JobProperties jobs, AttendanceProperties attendance, Clock clock) {
        if (!jobs.trigger().enabled()) {
            log.info("{} is disabled: no attendance.jobs.trigger.service-account (JOBS_TRIGGER_SERVICE_ACCOUNT)",
                    DAILY_JOBS_PATH);
            return JobTriggerTokens.rejectAll();
        }
        String audience = audience(jobs, attendance);
        log.info("{} accepts Google OIDC tokens of {} for audience {}", DAILY_JOBS_PATH,
                jobs.trigger().serviceAccount(), audience);
        return JobTriggerTokens.googleDecoder(audience, jobs.trigger().serviceAccount(), clock);
    }

    @Bean
    @Order(1)
    SecurityFilterChain jobTriggerFilterChain(HttpSecurity http, JwtDecoder decoder, RateLimiter rateLimiter,
                                              PepperedHasher hasher) throws Exception {
        AuthenticationEntryPoint unauthorized = new SilentEntryPoint();
        http
                .securityMatcher(DAILY_JOBS_PATH)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, DAILY_JOBS_PATH).hasAuthority(AUTHORITY)
                        .anyRequest().denyAll())
                // This chain matches only this path, so this exempts exactly this path.
                .csrf(csrf -> csrf.ignoringRequestMatchers(DAILY_JOBS_PATH))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt
                                .decoder(decoder)
                                .jwtAuthenticationConverter(JobTriggerSecurityConfig::toAuthentication))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.FORBIDDEN.value())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, denied) ->
                                response.setStatus(HttpStatus.FORBIDDEN.value())))
                .addFilterBefore(new PerIpRateLimitFilter(rateLimiter, hasher), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Every token that passed {@link JobTriggerTokens#validator} is the scheduler, so it gets the one authority. */
    private static AbstractAuthenticationToken toAuthentication(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(AUTHORITY)),
                jwt.getClaimAsString("email"));
    }

    /** 401 with no body and no error description; the reason is logged (it never contains the token). */
    private static final class SilentEntryPoint implements AuthenticationEntryPoint {

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().replaceAll("[\\r\\n\\t]", " ");
            log.warn("Rejected a call to {}: {}", DAILY_JOBS_PATH,
                    reason.length() > 200 ? reason.substring(0, 200) : reason);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        }
    }

    /** Not a bean: as a bean Spring Boot would also register it as a servlet filter for every path. */
    static final class PerIpRateLimitFilter extends OncePerRequestFilter {

        private final RateLimiter rateLimiter;
        private final PepperedHasher hasher;

        PerIpRateLimitFilter(RateLimiter rateLimiter, PepperedHasher hasher) {
            this.rateLimiter = rateLimiter;
            this.hasher = hasher;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String key = "job:ip:" + hasher.hash("ip", request.getRemoteAddr());
            if (!rateLimiter.tryAcquire(key, PER_IP_PER_MINUTE)) {
                log.warn("Rate-limited a call to {}", DAILY_JOBS_PATH);
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
