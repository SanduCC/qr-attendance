package io.github.sanducc.attendance.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Mints tokens shaped like the Google-signed OIDC ID token Cloud Scheduler sends, signed with a throwaway RSA key
 * instead of Google's. The app under test trusts this key's public half (see {@link JobTriggerTestConfiguration}).
 */
public final class JobTriggerTestTokens {

    /** Must match {@code attendance.jobs.trigger.service-account} in application-test.yml. */
    public static final String SERVICE_ACCOUNT = "job-trigger@test-project.iam.gserviceaccount.com";
    /** The default audience: {@code attendance.public-base-url} (http://localhost) + the endpoint path. */
    public static final String AUDIENCE = "http://localhost/internal/jobs/daily";

    private final RSAKey key;
    private final NimbusJwtEncoder encoder;

    private JobTriggerTestTokens(RSAKey key) {
        this.key = key;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    public static JobTriggerTestTokens generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new JobTriggerTestTokens(new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public RSAPublicKey publicKey() {
        try {
            return key.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token Cloud Scheduler would send at {@code now}: valid for one hour. */
    public String valid(Instant now) {
        return sign(now, claims -> { });
    }

    /** A valid token at {@code now}, then changed by {@code customize} (e.g. a wrong audience). */
    public String sign(Instant now, Consumer<JwtClaimsSet.Builder> customize) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("https://accounts.google.com")
                .audience(List.of(AUDIENCE))
                .subject("104353212345678901234")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .claim("azp", "104353212345678901234")
                .claim("email", SERVICE_ACCOUNT)
                .claim("email_verified", true);
        customize.accept(claims);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(),
                claims.build())).getTokenValue();
    }

    /** {@code alg: none}: no signature at all, otherwise valid claims. */
    public static String unsigned(Instant now) {
        return new PlainJWT(googleLikeClaims(now)).serialize();
    }

    /** HS256 with an attacker-chosen secret (algorithm confusion): must be rejected, only RS256 is accepted. */
    public static String hmacSigned(Instant now) {
        try {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), googleLikeClaims(now));
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet googleLikeClaims(Instant now) {
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience(AUDIENCE)
                .subject("104353212345678901234")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1))))
                .claim("email", SERVICE_ACCOUNT)
                .claim("email_verified", true)
                .build();
    }
}
