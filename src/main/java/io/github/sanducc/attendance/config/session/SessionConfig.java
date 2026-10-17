package io.github.sanducc.attendance.config.session;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.boot.session.autoconfigure.SessionProperties;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
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
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * HTTP sessions live in Redis (Spring Session), so they survive deploys and are shared by every app instance.
 *
 * <p>We build the repository ourselves instead of using Boot's auto-configured one, to wrap it in
 * {@link ResilientSessionRepository}. Boot's session auto-configuration then backs off for the repository and still
 * registers the filter.
 *
 * <p>The timeout and the cookie are read from {@code server.servlet.session.*} here too, in every environment. Boot 4
 * does that only for an embedded server; when the context has a {@code ServletContext} at startup, which is the
 * case under MockMvc ({@code @SpringBootTest}), it treats the app as a WAR deployment and takes both from the
 * servlet context instead. The tests would then run with another timeout and cookie than production.
 *
 * <ul>
 *   <li>Non-indexed {@link RedisSessionRepository}: one hash per session with a TTL, no keyspace notifications,
 *       so no {@code CONFIG} command (Upstash doesn't allow it) and no find-by-principal index.</li>
 *   <li>Keys: {@code qra:session:sessions:<id>}, apart from the rate limiter's {@code rl:*} keys.</li>
 *   <li>Timeout: {@code spring.session.timeout} if set, else {@code server.servlet.session.timeout}.</li>
 *   <li>Cookie: {@code server.servlet.session.cookie.*} ({@code SESSION}, HttpOnly, Secure, SameSite=Lax).</li>
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
                                                        SessionProperties sessionProperties,
                                                        ServerProperties serverProperties) {
        Duration timeout = sessionProperties.getTimeout() != null ? sessionProperties.getTimeout()
                : serverProperties.getServlet().getSession().getTimeout();
        return new ResilientSessionRepository(redisSessionRepository(redisConnectionFactory, timeout), TOUCH_INTERVAL);
    }

    /**
     * The session cookie from {@code server.servlet.session.cookie.*}, mapped as Boot's embedded-server session
     * configuration maps it, customizers included. Spring Session uses this bean; Boot's own one backs off.
     */
    @Bean
    public DefaultCookieSerializer sessionCookieSerializer(
            ServerProperties serverProperties, ObjectProvider<DefaultCookieSerializerCustomizer> customizers) {
        Cookie cookie = serverProperties.getServlet().getSession().getCookie();
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        PropertyMapper map = PropertyMapper.get();
        map.from(cookie::getName).to(serializer::setCookieName);
        map.from(cookie::getDomain).to(serializer::setDomainName);
        map.from(cookie::getPath).to(serializer::setCookiePath);
        map.from(cookie::getHttpOnly).to(serializer::setUseHttpOnlyCookie);
        map.from(cookie::getSecure).to(serializer::setUseSecureCookie);
        map.from(cookie::getMaxAge).asInt(Duration::getSeconds).to(serializer::setCookieMaxAge);
        map.from(cookie::getSameSite).to(sameSite -> serializer.setSameSite(sameSite.attributeValue()));
        map.from(cookie::getPartitioned).to(serializer::setPartitioned);
        customizers.orderedStream().forEach(customizer -> customizer.customize(serializer));
        return serializer;
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
