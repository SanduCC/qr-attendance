package io.github.sanducc.attendance.session;

import io.github.sanducc.attendance.common.Hmac;
import io.github.sanducc.attendance.config.AttendanceProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Rotating QR tokens: {@code HMAC-SHA256(sessionId + timeWindow, secret)} with a 15 s window.
 * Only the current and the previous window are accepted (~30 s), so a photo of the code
 * sent to someone at home is useless by the time they open it.
 */
@Service
public class QrTokenService {

    /** 128 bits of the HMAC is plenty and keeps the QR code small enough to scan from the back row. */
    private static final int TOKEN_BYTES = 16;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long windowMillis;
    private final Clock clock;

    public QrTokenService(AttendanceProperties properties, Clock clock) {
        this.secret = properties.security().qrHmacSecret().getBytes(StandardCharsets.UTF_8);
        this.windowMillis = properties.qr().window().toMillis();
        this.clock = clock;
    }

    public record QrToken(String value, Instant windowEndsAt) {}

    public QrToken current(UUID sessionId) {
        long window = currentWindow();
        return new QrToken(B64.encodeToString(tokenBytes(sessionId, window)),
                Instant.ofEpochMilli((window + 1) * windowMillis));
    }

    public boolean isValid(UUID sessionId, String token) {
        if (sessionId == null || token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        byte[] presented;
        try {
            presented = B64_DECODER.decode(token);
        } catch (IllegalArgumentException e) {
            return false;
        }
        long window = currentWindow();
        // Evaluate both so timing doesn't reveal which window matched.
        boolean current = MessageDigest.isEqual(presented, tokenBytes(sessionId, window));
        boolean previous = MessageDigest.isEqual(presented, tokenBytes(sessionId, window - 1));
        return current | previous;
    }

    private long currentWindow() {
        return Math.floorDiv(clock.millis(), windowMillis);
    }

    private byte[] tokenBytes(UUID sessionId, long window) {
        return Arrays.copyOf(Hmac.sha256(secret, sessionId + ":" + window), TOKEN_BYTES);
    }
}
