package io.github.sanducc.attendance.config.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ConfigurableObjectInputStream;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * JDK serialization for HTTP session values in Redis, hardened in two ways:
 * <ul>
 *   <li><b>Never fails a read.</b> A value that can't be read back (class removed or changed by a deploy, a
 *       Spring upgrade, a corrupt or foreign blob) comes back as {@link #UNREADABLE}, never as an exception.
 *       {@link ResilientSessionRepository} then drops the whole session, so the user simply signs in again.</li>
 *   <li><b>Allowlist.</b> Only our app writes to this Redis (over TLS, with a password), but JDK deserialization
 *       of attacker-controlled bytes can run code. As defence in depth, an {@link ObjectInputFilter} only lets
 *       through the packages a session legitimately holds; anything else (known gadget chains live in
 *       {@code com.sun.*}, {@code javax.*}, commons-collections, groovy...) is rejected before it's instantiated.</li>
 * </ul>
 */
public final class SessionRedisSerializer implements RedisSerializer<Object> {

    /**
     * Packages a session may contain: JDK types (collections, {@code Instant}, {@code URL}...), Spring Security /
     * Spring Web (security context, OIDC user, OAuth2 authorization request and client, CSRF token, flash maps,
     * saved request), Nimbus (JWT claim containers) and our own classes. Everything else is rejected.
     */
    static final String ALLOWLIST = "java.**;org.springframework.**;com.nimbusds.**;io.github.sanducc.attendance.**;!*";

    /** Stand-in for a value that couldn't be deserialized. Never handed to application code. */
    public static final Object UNREADABLE = Unreadable.INSTANCE;

    private static final Logger log = LoggerFactory.getLogger(SessionRedisSerializer.class);
    private static final byte[] EMPTY = new byte[0];

    private final ObjectInputFilter filter = ObjectInputFilter.Config.createFilter(ALLOWLIST);
    private final ClassLoader classLoader;

    public SessionRedisSerializer() {
        this(SessionRedisSerializer.class.getClassLoader());
    }

    public SessionRedisSerializer(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public byte[] serialize(Object value) {
        if (value == null) {
            return EMPTY;
        }
        // A non-serializable attribute is a programming error: fail loudly (tests catch it).
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new SerializationException("Cannot serialize session value of type " + value.getClass().getName(), e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try (ObjectInputStream in = new ConfigurableObjectInputStream(new ByteArrayInputStream(bytes), classLoader)) {
            in.setObjectInputFilter(filter);
            return in.readObject();
        } catch (Exception | LinkageError e) {
            // Logged once per session (as a warning) by ResilientSessionRepository; details only at debug.
            log.debug("Unreadable session value ({}: {})", e.getClass().getName(), e.getMessage());
            return UNREADABLE;
        }
    }

    private enum Unreadable {
        INSTANCE
    }
}
