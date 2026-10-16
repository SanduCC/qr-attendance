package io.github.sanducc.attendance.support;

import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.config.JobProperties;
import io.github.sanducc.attendance.config.JobTriggerSecurityConfig;
import io.github.sanducc.attendance.config.JobTriggerTokens;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The job-trigger endpoint, but trusting {@link JobTriggerTestTokens}' key instead of Google's JWKS (no network in
 * tests). Everything else is the production setup: the same claim validation ({@link JobTriggerTokens#validator}),
 * the same audience and service account from the configuration, the same filter chain. Bean overriding is
 * disabled, so this is a differently-named {@code @Primary} decoder that wins the injection into the chain.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JobTriggerTestConfiguration {

    @Bean
    public JobTriggerTestTokens jobTriggerTestTokens() {
        return JobTriggerTestTokens.generate();
    }

    @Bean
    @Primary
    public JwtDecoder testJobTriggerJwtDecoder(JobTriggerTestTokens tokens, JobProperties jobs,
                                               AttendanceProperties attendance, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(tokens.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        return JobTriggerTokens.withValidation(decoder, JobTriggerSecurityConfig.audience(jobs, attendance),
                jobs.trigger().serviceAccount(), clock);
    }
}
