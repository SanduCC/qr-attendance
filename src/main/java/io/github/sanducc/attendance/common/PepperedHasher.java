package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.config.AttendanceProperties;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Keyed one-way hashes for values we must compare but never store raw (IP, device id).
 * The {@code purpose} prefix keeps hashes of the same input unlinkable across uses.
 */
@Component
public class PepperedHasher {

    private final byte[] pepper;

    public PepperedHasher(AttendanceProperties properties) {
        this.pepper = properties.security().hashPepper().getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String purpose, String value) {
        return HexFormat.of().formatHex(Hmac.sha256(pepper, purpose + ":" + value));
    }

    public byte[] sign(String purpose, String value) {
        return Hmac.sha256(pepper, "sig:" + purpose + ":" + value);
    }
}
