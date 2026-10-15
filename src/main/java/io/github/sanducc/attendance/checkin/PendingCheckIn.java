package io.github.sanducc.attendance.checkin;

import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Proof that this browser scanned a valid QR token, stored in the HTTP session at scan time.
 * Bound to the device cookie hash so it can't be completed from another browser.
 */
public record PendingCheckIn(UUID sessionId, Instant scannedAt, String deviceIdHash) implements Serializable {

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
