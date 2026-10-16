package io.github.sanducc.attendance.checkin;

import jakarta.servlet.http.HttpSession;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof that this browser scanned a valid QR token, stored in the HTTP session at scan time.
 * Bound to the device cookie hash so it can't be completed from another browser.
 *
 * <p>Stored in Redis (JDK serialization) and read back by whichever version of the app handles the next request.
 * Records are deserialized by component name: renaming or retyping a component makes old sessions unreadable
 * (they are dropped and the student scans again); a new component arrives as {@code null} from old sessions.
 */
public record PendingCheckIn(UUID sessionId, Instant scannedAt, String deviceIdHash) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String SESSION_KEY = PendingCheckIn.class.getName();

    public static PendingCheckIn from(HttpSession session) {
        return session == null ? null : (session.getAttribute(SESSION_KEY) instanceof PendingCheckIn p ? p : null);
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
