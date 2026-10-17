package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.checkin.Provider;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The signed-in person as the identity provider describes them. Students are keyed on
 * {@code (provider, subject)}; email and names are display data only.
 *
 * <p>{@code hostedDomain} is the domain the course domain lock (rule 7) compares:
 * <ul>
 *   <li>Google: the {@code hd} claim of the <b>ID token</b> (signature-checked by Spring Security), set by Google
 *       only for Google Workspace accounts; null for consumer Gmail accounts. Read from the ID token itself, never
 *       from userinfo or the email suffix.</li>
 *   <li>Email sign-in (#52): the domain of the address, whose inbox ownership the one-time code proved.</li>
 *   <li>Other providers: null.</li>
 * </ul>
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

    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication instanceof EmailAuthenticationToken emailLogin) {
            String email = emailLogin.getEmail();
            // The subject is the normalized address itself; the provider (not the address) keeps it apart from a
            // Google account with the same address. No names: the student types them on first check-in.
            return new AuthenticatedUser(Provider.EMAIL, email, email, true, null, null,
                    EmailAddresses.domainOf(email));
        }
        if (!(authentication instanceof OAuth2AuthenticationToken oauth)
                || !(oauth.getPrincipal() instanceof OidcUser user)) {
            throw new InsufficientAuthenticationException("Sign-in required");
        }
        Provider provider = Provider.fromRegistrationId(oauth.getAuthorizedClientRegistrationId());
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

    /** A student sign-in the check-in flow accepts: Google (OIDC) or the email code. */
    public static boolean isStudentSignIn(Authentication authentication) {
        return authentication instanceof EmailAuthenticationToken
                || authentication instanceof OAuth2AuthenticationToken oauth && oauth.getPrincipal() instanceof OidcUser;
    }
}
