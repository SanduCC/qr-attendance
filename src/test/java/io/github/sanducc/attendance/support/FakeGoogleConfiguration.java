package io.github.sanducc.attendance.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2UserAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

/**
 * Stands in for Google's token endpoint and ID-token verification, so tests can run the <b>real</b> OAuth2 login
 * ({@code /oauth2/authorization/google} → {@code /login/oauth2/code/google}) without network access: session id
 * rotation, the authorization request and the security context then really go through the HTTP session store.
 *
 * <p>The authorization {@code code} a test sends is the e-mail address to sign in as, optionally with a Workspace
 * {@code hd} claim ({@link #code}); the subject is
 * {@code "sub-" + email}. The nonce Spring Security stored with the authorization request is echoed back as a
 * hash, exactly as Google would, so nonce validation stays on. Google's userinfo endpoint is faked too: Spring
 * Security 7 calls it on every OIDC sign-in (6.x only when the token carried {@code profile} / {@code email}, as
 * Google's real tokens do), and the fake answers with the ID token's identity claims, as Google would. Nothing here
 * affects tests that use {@code oidcLogin()} (they never reach the login endpoints).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeGoogleConfiguration {

    public static final String GIVEN_NAME = "Ana";
    public static final String FAMILY_NAME = "Popescu";

    /**
     * The authorization code for signing in as {@code email}, with Google's {@code hd} (Workspace hosted domain)
     * claim in the ID token when {@code hostedDomain} isn't null. Consumer Gmail accounts have no {@code hd}.
     */
    public static String code(String email, String hostedDomain) {
        return hostedDomain == null ? email : email + HD_SEPARATOR + hostedDomain;
    }

    private static final String HD_SEPARATOR = "|";

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> fakeGoogleTokenEndpoint() {
        return grant -> {
            String code = grant.getAuthorizationExchange().getAuthorizationResponse().getCode();
            int separator = code.indexOf(HD_SEPARATOR);
            String email = separator < 0 ? code : code.substring(0, separator);
            String hostedDomain = separator < 0 ? null : code.substring(separator + 1);
            String nonce = grant.getAuthorizationExchange().getAuthorizationRequest()
                    .getAttribute(OidcParameterNames.NONCE);
            String idToken = email + " " + sha256(nonce) + (hostedDomain == null ? "" : " " + hostedDomain);
            return OAuth2AccessTokenResponse.withToken("fake-access-token")
                    .tokenType(OAuth2AccessToken.TokenType.BEARER)
                    .expiresIn(3600)
                    .scopes(Set.of("openid"))
                    .additionalParameters(Map.of(OidcParameterNames.ID_TOKEN, idToken))
                    .build();
        };
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> fakeGoogleIdTokenDecoder() {
        return registration -> token -> {
            String[] parts = token.split(" ");
            Instant now = Instant.now();
            Jwt.Builder jwt = Jwt.withTokenValue(token)
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
                    .claim("family_name", FAMILY_NAME);
            if (parts.length > 2) {
                jwt.claim("hd", parts[2]);
            }
            return jwt.build();
        };
    }

    /**
     * Google's userinfo endpoint: plugged into the app's {@link OidcUserService} (AppOidcUserService), whose
     * userinfo call would otherwise go to Google over the network. Answers with the subject, email and names of the
     * ID token. Not {@code hd}: the app must read the hosted domain from the ID token itself, never from userinfo.
     */
    @Bean
    static BeanPostProcessor fakeGoogleUserInfoEndpoint() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof OidcUserService userService) {
                    userService.setOauth2UserService(FakeGoogleConfiguration::userInfo);
                }
                return bean;
            }
        };
    }

    private static OAuth2User userInfo(OAuth2UserRequest request) {
        OidcIdToken idToken = ((OidcUserRequest) request).getIdToken();
        Map<String, Object> claims = new LinkedHashMap<>();
        for (String claim : List.of("sub", "email", "email_verified", "given_name", "family_name")) {
            Object value = idToken.getClaim(claim);
            if (value != null) {
                claims.put(claim, value);
            }
        }
        return new DefaultOAuth2User(Set.of(new OAuth2UserAuthority(claims)), claims, "sub");
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
