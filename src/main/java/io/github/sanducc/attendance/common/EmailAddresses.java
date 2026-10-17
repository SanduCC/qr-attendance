package io.github.sanducc.attendance.common;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Email addresses as identities for the email sign-in (#52).
 *
 * <p><b>Normalized</b> = trimmed and lowercased, nothing else: dots and {@code +tags} are kept, because whether
 * {@code a.b+x@gmail.com} reaches the same inbox as {@code ab@gmail.com} is the mail provider's business, not ours.
 * The normalized form is the student's {@code provider_subject}.
 *
 * <p><b>Valid</b> = a basic, conservative syntax check (ASCII, one {@code @}, a dot-atom local part, a host name with
 * at least two labels, at most 254 characters). Nothing is ever sent to an address that fails it.
 */
public final class EmailAddresses {

    /** RFC 5321 path limit minus the angle brackets. */
    public static final int MAX_LENGTH = 254;
    private static final int MAX_LOCAL_LENGTH = 64;

    private static final Pattern LOCAL = Pattern.compile("^[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*$");
    /** Same host-name rule as the domain lock ({@code course.WorkspaceDomains}). */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{3,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$");

    private EmailAddresses() {
    }

    /** The normalized address if {@code raw} is a valid one, else empty. */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String email = raw.strip().toLowerCase(Locale.ROOT);
        if (email.isEmpty() || email.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at > MAX_LOCAL_LENGTH) {
            return Optional.empty();
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        if (!LOCAL.matcher(local).matches() || !DOMAIN.matcher(domain).matches()) {
            return Optional.empty();
        }
        return Optional.of(email);
    }

    /** The part after the {@code @} of a normalized address. */
    public static String domainOf(String normalizedEmail) {
        return normalizedEmail.substring(normalizedEmail.lastIndexOf('@') + 1);
    }

    /** For logs: {@code ana.popescu@x.ro} becomes {@code a***@x.ro}. Never log the full address. */
    public static String mask(String email) {
        if (email == null) {
            return "(none)";
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
