package io.github.sanducc.attendance.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.support.MutableClock;
import io.github.sanducc.attendance.support.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Anti-cheat rule 1: rotating token, current + previous 15 s window only. */
class QrTokenServiceTest {

    /** Exactly on a 15 s window boundary. */
    private static final Instant WINDOW_START = Instant.parse("2026-10-16T06:00:00Z");

    private final UUID sessionId = UUID.fromString("7d7a3c8e-31a4-4a59-9f5e-0a8f3b4c2d11");
    private MutableClock clock;
    private QrTokenService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(WINDOW_START.plusSeconds(5));
        service = new QrTokenService(TestProperties.defaults(), clock);
    }

    @Test
    void currentWindowTokenIsAccepted() {
        String token = service.current(sessionId).value();

        assertThat(service.isValid(sessionId, token)).isTrue();
    }

    @Test
    void tokenIsStableWithinAWindowAndRotatesAtTheBoundary() {
        clock.set(WINDOW_START);
        String first = service.current(sessionId).value();
        clock.set(WINDOW_START.plusMillis(14_999));
        assertThat(service.current(sessionId).value()).isEqualTo(first);

        clock.set(WINDOW_START.plusSeconds(15));
        assertThat(service.current(sessionId).value()).isNotEqualTo(first);
    }

    @Test
    void previousWindowTokenIsAccepted() {
        String token = service.current(sessionId).value();

        clock.advance(Duration.ofSeconds(15));

        assertThat(service.isValid(sessionId, token)).isTrue();
    }

    @Test
    void tokenIsValidUntilTheEndOfTheNextWindowAndNoLonger() {
        clock.set(WINDOW_START);
        String token = service.current(sessionId).value();

        clock.set(WINDOW_START.plusMillis(29_999));
        assertThat(service.isValid(sessionId, token)).isTrue();

        clock.set(WINDOW_START.plusSeconds(30));
        assertThat(service.isValid(sessionId, token)).isFalse();
    }

    @Test
    void tokenTwoWindowsOldIsRejected() {
        String token = service.current(sessionId).value();

        clock.advance(Duration.ofSeconds(30));

        assertThat(service.isValid(sessionId, token)).isFalse();
    }

    @Test
    void tokenFromAFutureWindowIsRejected() {
        clock.advance(Duration.ofSeconds(15));
        String nextWindowToken = service.current(sessionId).value();
        clock.advance(Duration.ofSeconds(-15));

        assertThat(service.isValid(sessionId, nextWindowToken)).isFalse();
    }

    @Test
    void tokenForAnotherSessionIsRejected() {
        String token = service.current(sessionId).value();

        assertThat(service.isValid(UUID.randomUUID(), token)).isFalse();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        QrTokenService other = new QrTokenService(
                TestProperties.with("another-secret-another-secret-another-secret", TestProperties.PEPPER, true),
                clock);
        String forged = other.current(sessionId).value();

        assertThat(service.isValid(sessionId, forged)).isFalse();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = service.current(sessionId).value();
        char first = token.charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + token.substring(1);

        assertThat(service.isValid(sessionId, tampered)).isFalse();
        assertThat(service.isValid(sessionId, token.substring(0, token.length() - 2))).isFalse();
        assertThat(service.isValid(sessionId, token + "AA")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"garbage!", "not base64 at all", "%%%", "////", "a"})
    void garbageTokensAreRejected(String token) {
        assertThat(service.isValid(sessionId, token)).isFalse();
    }

    @Test
    void overlongTokenIsRejected() {
        assertThat(service.isValid(sessionId, "A".repeat(65))).isFalse();
        assertThat(service.isValid(sessionId, "A".repeat(10_000))).isFalse();
    }

    @Test
    void nullSessionIsRejected() {
        String token = service.current(sessionId).value();

        assertThat(service.isValid(null, token)).isFalse();
    }

    @Test
    void tokenIsUrlSafeAndShort() {
        String token = service.current(sessionId).value();

        assertThat(token).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void windowEndsAtIsTheEndOfTheCurrentWindow() {
        clock.set(WINDOW_START.plusSeconds(5));
        assertThat(service.current(sessionId).windowEndsAt()).isEqualTo(WINDOW_START.plusSeconds(15));

        clock.set(WINDOW_START);
        assertThat(service.current(sessionId).windowEndsAt()).isEqualTo(WINDOW_START.plusSeconds(15));

        clock.set(WINDOW_START.plusMillis(14_999));
        assertThat(service.current(sessionId).windowEndsAt()).isEqualTo(WINDOW_START.plusSeconds(15));

        clock.set(WINDOW_START.plusSeconds(15));
        assertThat(service.current(sessionId).windowEndsAt()).isEqualTo(WINDOW_START.plusSeconds(30));
    }
}
