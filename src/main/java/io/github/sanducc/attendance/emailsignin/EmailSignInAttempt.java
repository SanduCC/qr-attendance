package io.github.sanducc.attendance.emailsignin;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * "A code was mailed to {@code email} for this browser at {@code sentAt}", in the HTTP session between the email
 * and the code pages. The code itself is never here (only its hash, in Redis); this only tells the code page which
 * address the typed code belongs to, and when "Send a new code" is allowed again.
 *
 * <p>Stored in Redis (JDK serialization): renaming or retyping a component makes old sessions unreadable.
 */
public record EmailSignInAttempt(String email, Instant sentAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SESSION_KEY = EmailSignInAttempt.class.getName();

    public static EmailSignInAttempt from(HttpSession session) {
        return session == null ? null
                : (session.getAttribute(SESSION_KEY) instanceof EmailSignInAttempt a ? a : null);
    }

    public void storeIn(HttpSession session) {
        session.setAttribute(SESSION_KEY, this);
    }

    public static void clear(HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
    }
}
