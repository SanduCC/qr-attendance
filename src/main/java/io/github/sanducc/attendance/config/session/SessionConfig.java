package io.github.sanducc.attendance.config.session;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import org.springframework.boot.session.autoconfigure.SessionTimeout;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.FlushMode;
import org.springframework.session.SaveMode;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * HTTP sessions live in Redis (Spring Session), so they survive deploys and are shared by every app instance.
 *
 * <p>We build the repository ourselves instead of using Boot's auto-configured one, to wrap it in
 * {@link ResilientSessionRepository}. Boot's session auto-configuration then backs off for the repository but
 * still provides the cookie ({@code server.servlet.session.cookie.*}: {@code SESSION}, HttpOnly, Secure,
 * SameSite=Lax) and registers the filter.
 *
 * <ul>
 *   <li>Non-indexed {@link RedisSessionRepository}: one hash per session with a TTL, no keyspace notifications,
 *       so no {@code CONFIG} command (Upstash doesn't allow it) and no find-by-principal index.</li>
 *   <li>Keys: {@code qra:session:sessions:<id>}, apart from the rate limiter's {@code rl:*} keys.</li>
 *   <li>Timeout: {@code server.servlet.session.timeout} (or {@code spring.session.timeout}), as before: Boot's
 *       {@link SessionTimeout} applies that rule.</li>
 *   <li>Flush on save (end of request) and save only attributes that were set: the cheapest modes.</li>
 *   <li>Values use JDK serialization, see {@link SessionRedisSerializer}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableSpringHttpSession
public class SessionConfig {

    public static final String NAMESPACE = "qra:session";

    /** How stale the stored last-access time may get before a read-only request refreshes it. */
    public static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    @Bean
    public ResilientSessionRepository sessionRepository(RedisConnectionFactory redisConnectionFactory,
                                                        SessionTimeout sessionTimeout) {
        return new ResilientSessionRepository(
                redisSessionRepository(redisConnectionFactory, sessionTimeout.getTimeout()), TOUCH_INTERVAL);
    }

    /** Fail closed with a clear 503 page when Redis is down; registered just outside Spring Session's filter. */
    @Bean
    public FilterRegistrationBean<SessionStoreUnavailableFilter> sessionStoreUnavailableFilter() {
        FilterRegistrationBean<SessionStoreUnavailableFilter> registration =
                new FilterRegistrationBean<>(new SessionStoreUnavailableFilter());
        registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ERROR, DispatcherType.ASYNC);
        return registration;
    }

    /**
     * The plain Redis repository. Public and static so tests can open a second, independent repository on the
     * same Redis, e.g. to check that a session written by one instance is readable after a "restart".
     */
    public static RedisSessionRepository redisSessionRepository(RedisConnectionFactory connectionFactory,
                                                                Duration timeout) {
        SessionRedisSerializer values = new SessionRedisSerializer();
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(StringRedisSerializer.UTF_8);
        template.setHashKeySerializer(StringRedisSerializer.UTF_8);
        template.setValueSerializer(values);
        template.setHashValueSerializer(values);
        template.afterPropertiesSet();

        RedisSessionRepository repository = new RedisSessionRepository(template);
        repository.setRedisKeyNamespace(NAMESPACE);
        repository.setDefaultMaxInactiveInterval(timeout);
        repository.setFlushMode(FlushMode.ON_SAVE);
        repository.setSaveMode(SaveMode.ON_SET_ATTRIBUTE);
        return repository;
    }
}
