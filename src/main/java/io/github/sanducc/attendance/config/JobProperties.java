package io.github.sanducc.attendance.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the background jobs (retention purge, auto-close of expired sessions) are triggered.
 *
 * @param inAppSchedule run them on the in-app {@code @Scheduled} timers (docker compose / VPS). Turn it off where an
 *                      external scheduler calls {@code POST /internal/jobs/daily} instead (Cloud Run scales to zero,
 *                      so in-app timers don't fire reliably there).
 * @param trigger       who may call {@code POST /internal/jobs/daily}
 */
@Validated
@ConfigurationProperties("attendance.jobs")
public record JobProperties(boolean inAppSchedule, @Valid @NotNull Trigger trigger) {

    /**
     * @param serviceAccount the one Google service account (the {@code email} claim of its Google-signed OIDC token)
     *                       allowed to trigger the jobs. Empty = the endpoint rejects every call.
     * @param audience       the exact {@code aud} the token must carry. Empty = {@code PUBLIC_BASE_URL} + the path.
     */
    public record Trigger(@Email String serviceAccount, String audience) {

        public Trigger {
            serviceAccount = blankToNull(serviceAccount);
            serviceAccount = serviceAccount == null ? null : serviceAccount.toLowerCase(Locale.ROOT);
            audience = blankToNull(audience);
        }

        public boolean enabled() {
            return serviceAccount != null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
