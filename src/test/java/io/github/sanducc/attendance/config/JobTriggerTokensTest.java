package io.github.sanducc.attendance.config;

import static io.github.sanducc.attendance.support.JobTriggerTestTokens.AUDIENCE;
import static io.github.sanducc.attendance.support.JobTriggerTestTokens.SERVICE_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanducc.attendance.support.JobTriggerTestTokens;
import io.github.sanducc.attendance.support.MutableClock;
import io.github.sanducc.attendance.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The claim checks on the Cloud Scheduler OIDC token, with the real Nimbus signature verification against a test
 * key (production uses Google's JWKS instead). No Docker needed.
 */
class JobTriggerTokensTest {

    private static final JobTriggerTestTokens TOKENS = JobTriggerTestTokens.generate();
    private static final JobTriggerTestTokens OTHER_KEY = JobTriggerTestTokens.generate();

    private final MutableClock clock = new MutableClock();
    private final Instant now = clock.instant();
    private final JwtDecoder decoder = JobTriggerTokens.withValidation(
            NimbusJwtDecoder.withPublicKey(TOKENS.publicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build(),
            AUDIENCE, SERVICE_ACCOUNT, clock);

    @Test
    void acceptsTheSchedulerTokenForTheExactAudience() {
        Jwt jwt = decoder.decode(TOKENS.valid(now));

        assertThat(jwt.getClaimAsString("email")).isEqualTo(SERVICE_ACCOUNT);
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
    }

    @Test
    void acceptsTheBareIssuerFormGoogleAlsoUses() {
        assertThat(decoder.decode(TOKENS.sign(now, c -> c.issuer("accounts.google.com")))).isNotNull();
    }

    @Test
    void rejectsAWrongAudience() {
        assertRejected(TOKENS.sign(now, c -> c.audience(List.of("https://other-service.run.app"))));
        assertRejected(TOKENS.sign(now, c -> c.audience(List.of("http://localhost"))));
        assertRejected(TOKENS.sign(now, c -> c.audience(List.of(AUDIENCE + "/"))));
    }

    @Test
    void rejectsAnExtraAudience() {
        assertRejected(TOKENS.sign(now, c -> c.audience(List.of(AUDIENCE, "https://other-service.run.app"))));
    }

    @Test
    void rejectsAMissingAudience() {
        assertRejected(TOKENS.sign(now, c -> c.claims(claims -> claims.remove(JwtClaimNames.AUD))));
    }

    @Test
    void rejectsAnotherServiceAccount() {
        assertRejected(TOKENS.sign(now, c -> c.claim("email", "attacker@other-project.iam.gserviceaccount.com")));
        assertRejected(TOKENS.sign(now, c -> c.claim("email", SERVICE_ACCOUNT.toUpperCase())));
        assertRejected(TOKENS.sign(now, c -> c.claims(claims -> claims.remove("email"))));
    }

    @Test
    void rejectsAnUnverifiedEmail() {
        assertRejected(TOKENS.sign(now, c -> c.claim("email_verified", false)));
        assertRejected(TOKENS.sign(now, c -> c.claim("email_verified", "true")));
        assertRejected(TOKENS.sign(now, c -> c.claims(claims -> claims.remove("email_verified"))));
    }

    @Test
    void rejectsAnotherIssuer() {
        assertRejected(TOKENS.sign(now, c -> c.issuer("https://token.actions.githubusercontent.com")));
        assertRejected(TOKENS.sign(now, c -> c.claims(claims -> claims.remove(JwtClaimNames.ISS))));
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = TOKENS.valid(now);
        clock.advance(Duration.ofHours(1).plusMinutes(2));

        assertRejected(token);
    }

    @Test
    void toleratesOneMinuteOfClockSkew() {
        String token = TOKENS.valid(now);
        clock.advance(Duration.ofHours(1).plusSeconds(30));

        assertThat(decoder.decode(token)).isNotNull();
    }

    @Test
    void rejectsATokenWithoutExpiry() {
        assertRejected(TOKENS.sign(now, c -> c.claims(claims -> claims.remove(JwtClaimNames.EXP))));
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        assertRejected(OTHER_KEY.valid(now));
    }

    @Test
    void rejectsATamperedPayload() {
        String[] parts = TOKENS.valid(now).split("\\.");
        String otherPayload = OTHER_KEY.sign(now, c -> c.subject("someone-else")).split("\\.")[1];

        assertRejected(parts[0] + "." + otherPayload + "." + parts[2]);
    }

    @Test
    void rejectsAnUnsignedToken() {
        assertRejected(JobTriggerTestTokens.unsigned(now));
    }

    @Test
    void rejectsAnHmacSignedToken() {
        assertRejected(JobTriggerTestTokens.hmacSigned(now));
    }

    @Test
    void rejectsGarbage() {
        assertRejected("not-a-jwt");
        assertRejected("");
    }

    @Test
    void rejectAllRejectsEvenAValidToken() {
        assertThatThrownBy(() -> JobTriggerTokens.rejectAll().decode(TOKENS.valid(now)))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void audienceDefaultsToThePublicBaseUrlPlusThePath() {
        AttendanceProperties attendance = TestProperties.defaults();

        assertThat(JobTriggerSecurityConfig.audience(
                new JobProperties(false, new JobProperties.Trigger(SERVICE_ACCOUNT, null)), attendance))
                .isEqualTo("http://localhost/internal/jobs/daily");
        assertThat(JobTriggerSecurityConfig.audience(
                new JobProperties(false, new JobProperties.Trigger(SERVICE_ACCOUNT, " https://x.run.app/jobs ")),
                attendance))
                .isEqualTo("https://x.run.app/jobs");
    }

    @Test
    void triggerIsDisabledWithoutAServiceAccount() {
        assertThat(new JobProperties.Trigger(null, null).enabled()).isFalse();
        assertThat(new JobProperties.Trigger("  ", null).enabled()).isFalse();
        JobProperties.Trigger trigger = new JobProperties.Trigger(" Job-Trigger@P.iam.gserviceaccount.com ", "");
        assertThat(trigger.enabled()).isTrue();
        assertThat(trigger.serviceAccount()).isEqualTo("job-trigger@p.iam.gserviceaccount.com");
        assertThat(trigger.audience()).isNull();
    }

    private void assertRejected(String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
