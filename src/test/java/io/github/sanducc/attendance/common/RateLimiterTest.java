package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sanducc.attendance.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Anti-cheat rule 8: fixed one-minute windows in Redis; fail open when Redis is down. */
class RateLimiterTest {

    private final Map<String, Long> counters = new HashMap<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-16T06:00:05Z"));
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(inv -> counters.merge(inv.getArgument(0), 1L, Long::sum));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        limiter = new RateLimiter(redis, clock);
    }

    @Test
    void allowsUpToTheLimitThenDenies() {
        for (int i = 1; i <= 3; i++) {
            assertThat(limiter.tryAcquire("dev:abc", 3)).as("attempt %d", i).isTrue();
        }
        assertThat(limiter.tryAcquire("dev:abc", 3)).isFalse();
        assertThat(limiter.tryAcquire("dev:abc", 3)).isFalse();
    }

    @Test
    void setsAnExpiryOnlyOnTheFirstHitOfAWindow() {
        limiter.tryAcquire("dev:abc", 3);
        limiter.tryAcquire("dev:abc", 3);
        limiter.tryAcquire("dev:abc", 3);

        verify(redis, times(1)).expire(anyString(), eq(Duration.ofSeconds(90)));
    }

    @Test
    void keysAreIndependent() {
        assertThat(limiter.tryAcquire("dev:a", 1)).isTrue();
        assertThat(limiter.tryAcquire("dev:a", 1)).isFalse();

        assertThat(limiter.tryAcquire("dev:b", 1)).isTrue();
        assertThat(limiter.tryAcquire("ip:a", 1)).isTrue();
    }

    @Test
    void keyIncludesTheMinuteSoTheNextMinuteStartsFresh() {
        long minute = clock.millis() / 60_000;
        assertThat(limiter.tryAcquire("dev:a", 1)).isTrue();
        assertThat(limiter.tryAcquire("dev:a", 1)).isFalse();
        assertThat(counters).containsKey("rl:dev:a:" + minute);

        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("dev:a", 1)).isTrue();
        assertThat(counters).containsKey("rl:dev:a:" + (minute + 1));
    }

    @Test
    void longerWindowsCountAcrossMinutesAndExpireAfterTheWindow() {
        Duration window = Duration.ofMinutes(15);
        long index = clock.millis() / window.toMillis();
        assertThat(limiter.tryAcquire("mail-to:x", 2, window)).isTrue();
        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("mail-to:x", 2, window)).isTrue();
        assertThat(limiter.tryAcquire("mail-to:x", 2, window)).isFalse();
        assertThat(counters).containsKey("rl:mail-to:x:" + index);
        verify(redis, times(1)).expire(anyString(), eq(window.plusSeconds(30)));

        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.tryAcquire("mail-to:x", 2, window)).isTrue();
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("Redis down"));

        for (int i = 0; i < 20; i++) {
            assertThat(limiter.tryAcquire("dev:abc", 1)).isTrue();
        }
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void failsOpenWhenExpireFails() {
        when(redis.expire(anyString(), any(Duration.class))).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(limiter.tryAcquire("dev:abc", 1)).isTrue();
    }

    @Test
    void allowsWhenRedisReturnsNoCount() {
        // e.g. inside a pipeline/transaction, INCR returns null.
        when(values.increment(anyString())).thenReturn(null);

        assertThat(limiter.tryAcquire("dev:abc", 1)).isTrue();
        assertThat(limiter.tryAcquire("dev:abc", 1)).isTrue();
    }
}
