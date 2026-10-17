package io.github.sanducc.attendance.checkin;

import java.util.Locale;

/**
 * Who vouches for a student's identity. {@code GOOGLE} and {@code APPLE} are OAuth providers (subject = the
 * {@code sub} claim); {@code EMAIL} is the one-time code sign-in (#52), whose subject is the normalized address.
 */
public enum Provider {
    GOOGLE,
    APPLE,
    /** Signed in with a one-time code mailed to the address; never an OAuth registration id. */
    EMAIL;

    public static Provider fromRegistrationId(String registrationId) {
        return switch (registrationId.toLowerCase(Locale.ROOT)) {
            case "google" -> GOOGLE;
            case "apple" -> APPLE;
            default -> throw new IllegalArgumentException("Unsupported identity provider: " + registrationId);
        };
    }
}
