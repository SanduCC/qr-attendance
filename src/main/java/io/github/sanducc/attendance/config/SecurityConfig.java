package io.github.sanducc.attendance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.CrossOriginEmbedderPolicyHeaderWriter.CrossOriginEmbedderPolicy;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.CrossOriginResourcePolicyHeaderWriter.CrossOriginResourcePolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

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

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AppOidcUserService oidcUserService,
                                            LoginSuccessHandler loginSuccessHandler) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/login", "/privacy", "/error",
                                "/css/**", "/js/**", "/icons/**",
                                "/manifest.webmanifest", "/sw.js", "/favicon.ico",
                                "/actuator/health").permitAll()
                        // Scan landing is public: the QR token is validated before sign-in.
                        .requestMatchers(HttpMethod.GET, "/a/*").permitAll()
                        .requestMatchers("/a/**").authenticated()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/teacher/**", "/sessions/**").hasRole("TEACHER")
                        .anyRequest().authenticated())
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
