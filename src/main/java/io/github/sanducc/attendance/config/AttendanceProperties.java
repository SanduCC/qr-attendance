package io.github.sanducc.attendance.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Typed view of the {@code attendance.*} settings. Secrets come only from env vars. */
@Validated
@ConfigurationProperties("attendance")
public record AttendanceProperties(
        @NotBlank String publicBaseUrl,
        @NotNull ZoneId displayZone,
        List<String> adminEmails,
        @Valid @NotNull Security security,
        @Valid @NotNull Sessions session,
        @Valid @NotNull Qr qr,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Retention retention) {

    public AttendanceProperties {
        publicBaseUrl = publicBaseUrl == null ? null : publicBaseUrl.replaceAll("/+$", "");
        adminEmails = adminEmails == null ? List.of() : adminEmails.stream()
                .filter(Objects::nonNull)
                .map(e -> e.trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty())
                .toList();
    }

    public record Security(
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String qrHmacSecret,
            @NotBlank @Size(min = 32, message = "must be at least 32 characters") String hashPepper,
            boolean secureCookies) {}

    public record Sessions(
            @NotNull Duration defaultDuration,
            @NotNull Duration maxDuration,
            @NotNull Duration checkInGrace) {}

    public record Qr(@NotNull Duration window) {}

    public record RateLimit(@Min(1) int perIpPerMinute, @Min(1) int perDevicePerMinute) {}

    public record Retention(
            @NotBlank @Pattern(regexp = "\\d{2}-\\d{2}") String academicYearEnd,
            @Min(0) int graceDays) {

        public MonthDay academicYearEndDay() {
            return MonthDay.parse("--" + academicYearEnd);
        }
    }
}
