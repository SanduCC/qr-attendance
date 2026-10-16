package io.github.sanducc.attendance.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verification of the Google-signed OIDC ID token that Cloud Scheduler sends with each call to
 * {@code POST /internal/jobs/daily}. The Cloud Run service is public ({@code --allow-unauthenticated}), so Cloud
 * Run doesn't check the token: the app must. Signature checking (RS256 against Google's published keys) is
 * Spring Security / Nimbus; this class only adds the claims that make the token <em>ours</em>:
 * <ul>
 *   <li>{@code iss} is Google,</li>
 *   <li>{@code aud} is exactly the configured audience (the endpoint URL), so a Google token minted for any other
 *       service is useless here,</li>
 *   <li>{@code email} is exactly the scheduler service account and {@code email_verified} is true,</li>
 *   <li>{@code exp} is present and the token isn't expired (60 s clock skew). Google's tokens live one hour.</li>
 * </ul>
 */
public final class JobTriggerTokens {

    /** Google's OAuth 2.0 / OIDC signing keys (JWKS). Fetched lazily on the first call, then cached. */
    public static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";
    /** Google ID tokens use either form; service-account tokens use the https one. */
    public static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Duration JWKS_TIMEOUT = Duration.ofSeconds(5);

    private JobTriggerTokens() {
    }

    /** Production decoder: Google's keys, RS256 only, plus {@link #validator}. */
    public static JwtDecoder googleDecoder(String audience, String serviceAccount, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplateBuilder()
                        .connectTimeout(JWKS_TIMEOUT)
                        .readTimeout(JWKS_TIMEOUT)
                        .build())
                .build();
        return withValidation(decoder, audience, serviceAccount, clock);
    }

    /** Applies {@link #validator} to a signature-checking decoder (Google's keys in production, a test key in tests). */
    public static NimbusJwtDecoder withValidation(NimbusJwtDecoder decoder, String audience, String serviceAccount,
                                                  Clock clock) {
        decoder.setJwtValidator(validator(audience, serviceAccount, clock));
        return decoder;
    }

    /** Used when no trigger service account is configured: every token is rejected. */
    public static JwtDecoder rejectAll() {
        return token -> {
            throw new BadJwtException("No job trigger service account configured");
        };
    }

    public static OAuth2TokenValidator<Jwt> validator(String audience, String serviceAccount, Clock clock) {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(serviceAccount, "serviceAccount");
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        List<String> expectedAudience = List.of(audience);
        return new DelegatingOAuth2TokenValidator<>(List.of(
                // JwtTimestampValidator accepts a token without exp; we don't.
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                timestamps,
                new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                        iss -> iss != null && GOOGLE_ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && expectedAudience.equals(List.copyOf(aud))),
                new JwtClaimValidator<Object>("email", serviceAccount::equals),
                new JwtClaimValidator<Object>("email_verified", Boolean.TRUE::equals)));
    }
}
