package io.github.sanducc.attendance.checkin;

import java.util.Locale;

public enum Provider {
    GOOGLE,
    APPLE;

    public static Provider fromRegistrationId(String registrationId) {
        return switch (registrationId.toLowerCase(Locale.ROOT)) {
            case "google" -> GOOGLE;
            case "apple" -> APPLE;
            default -> throw new IllegalArgumentException("Unsupported identity provider: " + registrationId);
        };
    }
}
