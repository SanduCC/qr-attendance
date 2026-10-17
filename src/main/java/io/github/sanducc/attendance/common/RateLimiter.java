package io.github.sanducc.attendance.common;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis (one minute unless a window is given). Fails open if Redis is unreachable: the
 * per-account and per-device rules are enforced by DB constraints regardless, and the email sign-in codes (#52)
 * can't be issued without Redis anyway.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    /** How long a counter outlives its window, so a slow clock on another instance still finds it. */
    private static final Duration TTL_SLACK = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Counts one attempt for {@code key}; returns false once the per-minute limit is exceeded. */
    public boolean tryAcquire(String key, int limitPerMinute) {
        return tryAcquire(key, limitPerMinute, ONE_MINUTE);
    }

    /**
     * Counts one attempt for {@code key} in the current fixed {@code window} (aligned to the epoch); returns false
     * once more than {@code limit} attempts were counted in it. Use one window per key prefix.
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        String redisKey = "rl:" + key + ":" + clock.millis() / window.toMillis();
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1) {
                redis.expire(redisKey, window.plus(TTL_SLACK));
            }
            return count == null || count <= limit;
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return true;
        }
    }
}
