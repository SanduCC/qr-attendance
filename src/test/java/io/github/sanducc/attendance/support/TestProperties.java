package io.github.sanducc.attendance.support;

import io.github.sanducc.attendance.config.AttendanceProperties;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/** Builds {@link AttendanceProperties} for unit tests, mirroring application.yml defaults. */
public final class TestProperties {

    public static final String QR_SECRET = "unit-test-qr-secret-0123456789abcdef-0123";
    public static final String PEPPER = "unit-test-hash-pepper-0123456789abcdef-012";

    private TestProperties() {
    }

    public static AttendanceProperties defaults() {
        return with(QR_SECRET, PEPPER, true);
    }

    public static AttendanceProperties with(String qrSecret, String pepper, boolean secureCookies) {
        return new AttendanceProperties(
                "http://localhost",
                ZoneId.of("Europe/Bucharest"),
                List.of(),
                new AttendanceProperties.Security(qrSecret, pepper, secureCookies, 1),
                new AttendanceProperties.Sessions(Duration.ofMinutes(15), Duration.ofHours(3), Duration.ofMinutes(3)),
                new AttendanceProperties.Qr(Duration.ofSeconds(15)),
                new AttendanceProperties.RateLimit(120, 10),
                new AttendanceProperties.Retention("09-30", 30));
    }
}
