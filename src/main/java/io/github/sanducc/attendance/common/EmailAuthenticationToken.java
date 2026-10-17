package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.config.AppOidcUserService;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * A student signed in with a one-time code mailed to {@link #getEmail()} (#52). It carries exactly one authority,
 * {@code ROLE_STUDENT}, and there is no way to give it another: the teacher and admin roles come only from a Google
 * sign-in checked against the admin-managed teacher list, so an email login never grants them, even for a
 * teacher's or an admin's address.
 *
 * <p>Stored in the HTTP session (Redis, JDK serialization) like the OAuth2 token.
 */
public final class EmailAuthenticationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String email;

    /** {@code email} is normalized ({@link EmailAddresses#normalize}) and its inbox ownership was proven. */
    public EmailAuthenticationToken(String email) {
        super(List.of(new SimpleGrantedAuthority(AppOidcUserService.ROLE_STUDENT)));
        this.email = Objects.requireNonNull(email);
        super.setAuthenticated(true);
    }

    public String getEmail() {
        return email;
    }

    @Override
    public Object getPrincipal() {
        return email;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return email;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        if (authenticated) {
            throw new IllegalArgumentException("Create a new token instead");
        }
        super.setAuthenticated(false);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EmailAuthenticationToken token && super.equals(token) && email.equals(token.email);
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + email.hashCode();
    }

    /** Masked: framework debug logs print authentication objects, and logs never get a full address. */
    @Override
    public String toString() {
        return getClass().getSimpleName() + " [Principal=" + EmailAddresses.mask(email) + ", Authorities="
                + getAuthorities() + "]";
    }
}
