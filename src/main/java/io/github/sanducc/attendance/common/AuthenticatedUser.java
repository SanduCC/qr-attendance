package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.checkin.Provider;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The signed-in person as the identity provider describes them. Students are keyed on
 * {@code (provider, subject)}; email and names are display data only.
 */
public record AuthenticatedUser(
        Provider provider,
        String subject,
        String email,
        boolean emailVerified,
        String givenName,
        String familyName) {

    public static AuthenticatedUser from(OAuth2AuthenticationToken authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw new InsufficientAuthenticationException("OIDC sign-in required");
        }
        return new AuthenticatedUser(
                Provider.fromRegistrationId(authentication.getAuthorizedClientRegistrationId()),
                user.getSubject(),
                user.getEmail(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.getGivenName(),
                user.getFamilyName());
    }
}
