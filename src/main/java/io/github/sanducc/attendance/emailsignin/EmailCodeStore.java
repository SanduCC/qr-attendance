package io.github.sanducc.attendance.emailsignin;

import io.github.sanducc.attendance.common.PepperedHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The one-time sign-in codes (#52), in Redis only.
 *
 * <ul>
 *   <li>A code is 6 random digits ({@link SecureRandom}), valid for {@code code-validity} (Redis TTL), single use.</li>
 *   <li>Bound to <b>this browser's HTTP session id plus the normalized address</b>: the Redis key is a peppered hash
 *       of both, so a code typed in another browser (another session) finds nothing. Neither the session id nor
 *       the address appears in the key.</li>
 *   <li>Only a peppered hash of the code (and of what it is bound to) is stored, never the code. Compared in
 *       constant time.</li>
 *   <li>At most {@code max-attempts} entries per code; the last wrong one deletes it. A new code for the same
 *       session and address replaces the old one.</li>
 * </ul>
 */
public class EmailCodeStore {

    static final String KEY_PREFIX = "qra:email-code:";

    // Redis hash fields: h = the code's hash, n = attempts so far, e = expiry (epoch millis, app clock).

    /** Replace any previous code, atomically with its TTL (a key without TTL would never go away). */
    private static final RedisScript<Long> ISSUE = new DefaultRedisScript<>("""
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'h', ARGV[1], 'n', '0', 'e', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return 1
            """, Long.class);

    /** Count one attempt, return {hash, attempts, expiry}; nil when there is no code (HINCRBY would create one). */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ATTEMPT = new DefaultRedisScript<>("""
            local h = redis.call('HGET', KEYS[1], 'h')
            if not h then return nil end
            local n = redis.call('HINCRBY', KEYS[1], 'n', 1)
            return {h, n, redis.call('HGET', KEYS[1], 'e')}
            """, List.class);

    public sealed interface Result {
        /** The code was right; it is now used up. */
        record Accepted() implements Result {}
        record Wrong(int attemptsLeft) implements Result {}
        /** That was the last allowed attempt (or one too many): the code is gone, a new one is needed. */
        record TooManyAttempts() implements Result {}
        /** No code for this session and address: expired, used, replaced or never sent. */
        record NoCode() implements Result {}
    }

    private final StringRedisTemplate redis;
    private final PepperedHasher hasher;
    private final EmailSignInProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EmailCodeStore(StringRedisTemplate redis, PepperedHasher hasher, EmailSignInProperties properties,
                          Clock clock) {
        this.redis = redis;
        this.hasher = hasher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Creates and stores a new code for this session and address, replacing any previous one; returns it. */
    public String issue(String httpSessionId, String email) {
        String code = "%06d".formatted(random.nextInt(1_000_000));
        long validity = properties.codeValidity().toMillis();
        redis.execute(ISSUE, List.of(key(httpSessionId, email)), codeHash(httpSessionId, email, code),
                Long.toString(validity), Long.toString(clock.millis() + validity));
        return code;
    }

    /** Checks a well-formed 6-digit {@code code}; every call counts as an attempt. */
    public Result verify(String httpSessionId, String email, String code) {
        String key = key(httpSessionId, email);
        List<?> stored = redis.execute(ATTEMPT, List.of(key));
        if (stored == null || stored.size() < 3) {
            return new Result.NoCode();
        }
        String storedHash = String.valueOf(stored.get(0));
        long attempts = ((Number) stored.get(1)).longValue();
        // The Redis TTL removes the code; the app clock decides too (the same clock as every other rule).
        if (clock.millis() >= Long.parseLong(String.valueOf(stored.get(2)))) {
            redis.delete(key);
            return new Result.NoCode();
        }
        int max = properties.maxAttempts();
        if (attempts > max) {
            redis.delete(key);
            return new Result.TooManyAttempts();
        }
        boolean matches = MessageDigest.isEqual(storedHash.getBytes(StandardCharsets.US_ASCII),
                codeHash(httpSessionId, email, code).getBytes(StandardCharsets.US_ASCII));
        if (matches) {
            // Single use: only the request that actually deletes the key wins (two tabs submitting at once).
            return Boolean.TRUE.equals(redis.delete(key)) ? new Result.Accepted() : new Result.NoCode();
        }
        if (attempts >= max) {
            redis.delete(key);
            return new Result.TooManyAttempts();
        }
        return new Result.Wrong((int) (max - attempts));
    }

    /** Drops the code, e.g. when it couldn't be mailed. */
    public void discard(String httpSessionId, String email) {
        redis.delete(key(httpSessionId, email));
    }

    String key(String httpSessionId, String email) {
        return KEY_PREFIX + hasher.hash("email-code-key", httpSessionId + "\n" + email);
    }

    private String codeHash(String httpSessionId, String email, String code) {
        return hasher.hash("email-code", httpSessionId + "\n" + email + "\n" + code);
    }
}
