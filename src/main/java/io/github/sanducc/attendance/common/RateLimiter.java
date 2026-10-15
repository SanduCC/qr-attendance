package io.github.sanducc.attendance.common;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed one-minute window counters in Redis. Fails open if Redis is unreachable: the
 * per-account and per-device rules are enforced by DB constraints regardless.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final Duration KEY_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** Counts one attempt for {@code key}; returns false once the per-minute limit is exceeded. */
    public boolean tryAcquire(String key, int limitPerMinute) {
        String redisKey = "rl:" + key + ":" + clock.millis() / 60_000;
        try {
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1) {
                redis.expire(redisKey, KEY_TTL);
            }
            return count == null || count <= limitPerMinute;
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
            return true;
        }
    }
}
