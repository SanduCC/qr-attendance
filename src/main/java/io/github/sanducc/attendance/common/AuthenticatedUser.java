package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.checkin.Provider;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The signed-in person as the identity provider describes them. Students are keyed on
 * {@code (provider, subject)}; email and names are display data only.
 *
 * <p>{@code hostedDomain} is the {@code hd} claim of the Google <b>ID token</b> (signature-checked by Spring
 * Security), set by Google only for Google Workspace accounts; null for consumer Gmail accounts and other providers.
 * It is read from the ID token itself, never from userinfo or the email suffix.
 */
public record AuthenticatedUser(
        Provider provider,
        String subject,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName,
        String hostedDomain) {

    /** Google's hosted-domain claim. */
    public static final String HOSTED_DOMAIN_CLAIM = "hd";

    public static AuthenticatedUser from(OAuth2AuthenticationToken authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw new InsufficientAuthenticationException("OIDC sign-in required");
        }
        Provider provider = Provider.fromRegistrationId(authentication.getAuthorizedClientRegistrationId());
        String hostedDomain = provider == Provider.GOOGLE && user.getIdToken() != null
                ? user.getIdToken().getClaimAsString(HOSTED_DOMAIN_CLAIM)
                : null;
        return new AuthenticatedUser(
                provider,
                user.getSubject(),
                user.getEmail(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.getGivenName(),
                user.getFamilyName(),
                hostedDomain);
    }
}
