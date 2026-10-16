package io.github.sanducc.attendance.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

/**
 * Stands in for Google's token endpoint and ID-token verification, so tests can run the <b>real</b> OAuth2 login
 * ({@code /oauth2/authorization/google} → {@code /login/oauth2/code/google}) without network access: session id
 * rotation, the authorization request and the security context then really go through the HTTP session store.
 *
 * <p>The authorization {@code code} a test sends is the e-mail address to sign in as; the subject is
 * {@code "sub-" + email}. The nonce Spring Security stored with the authorization request is echoed back as a
 * hash, exactly as Google would, so nonce validation stays on. Only the access token's scope is {@code openid},
 * so Spring Security doesn't call Google's userinfo endpoint. Nothing here affects tests that use
 * {@code oidcLogin()} (they never reach the login endpoints).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeGoogleConfiguration {

    public static final String GIVEN_NAME = "Ana";
    public static final String FAMILY_NAME = "Popescu";

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> fakeGoogleTokenEndpoint() {
        return grant -> {
            String email = grant.getAuthorizationExchange().getAuthorizationResponse().getCode();
            String nonce = grant.getAuthorizationExchange().getAuthorizationRequest()
                    .getAttribute(OidcParameterNames.NONCE);
            return OAuth2AccessTokenResponse.withToken("fake-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(3600)
                    .scopes(Set.of("openid"))
                    .additionalParameters(Map.of(OidcParameterNames.ID_TOKEN, email + " " + sha256(nonce)))
                    .build();
        };
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> fakeGoogleIdTokenDecoder() {
        return registration -> token -> {
            String[] parts = token.split(" ");
            Instant now = Instant.now();
            return Jwt.withTokenValue(token)
                    .header("alg", "RS256")
                    .issuer("https://accounts.google.com")
                    .subject("sub-" + parts[0])
                    .audience(List.of(registration.getClientId()))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .claim("nonce", parts[1])
                    .claim("email", parts[0])
                    .claim("email_verified", true)
                    .claim("given_name", GIVEN_NAME)
                    .claim("family_name", FAMILY_NAME)
                    .build();
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
