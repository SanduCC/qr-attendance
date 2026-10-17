package io.github.sanducc.attendance.emailsignin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Email sign-in for students without a Google account (#52): {@code attendance.email-sign-in.*}.
 *
 * @param enabled        off by default; turn on only once mail is configured (checked at startup)
 * @param from           the sender, e.g. {@code "QR Attendance <attendance@example.ro>"} (env {@code MAIL_FROM})
 * @param codeValidity   how long a code can be used
 * @param maxAttempts    wrong codes before the code is invalidated
 * @param resendCooldown minimum time between two codes for the same browser session
 * @param sendTimeout    how long a request waits for the SMTP server
 * @param limits         anti-abuse rate limits (Redis, fixed windows)
 */
@Validated
@ConfigurationProperties("attendance.email-sign-in")
public record EmailSignInProperties(
        boolean enabled,
        String from,
        @DefaultValue("10m") @NotNull Duration codeValidity,
        @DefaultValue("5") @Min(1) int maxAttempts,
        @DefaultValue("30s") @NotNull Duration resendCooldown,
        @DefaultValue("10s") @NotNull Duration sendTimeout,
        @DefaultValue @Valid @NotNull Limits limits) {

    public EmailSignInProperties {
        from = from == null || from.isBlank() ? null : from.strip();
    }

    /** Fixed-window limits; the window is in the name. All counters are keyed by hashes, never raw values. */
    public record Limits(
            @DefaultValue("3") @Min(1) int sendsPerEmailPer15Min,
            @DefaultValue("5") @Min(1) int sendsPerDevicePer15Min,
            @DefaultValue("60") @Min(1) int sendsPerIpPerHour,
            @DefaultValue("250") @Min(1) int sendsPerDay,
            @DefaultValue("10") @Min(1) int verifiesPerSessionPer15Min,
            @DefaultValue("150") @Min(1) int verifiesPerIpPer15Min) {}
}
