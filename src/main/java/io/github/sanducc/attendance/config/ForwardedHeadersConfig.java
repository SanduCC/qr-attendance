package io.github.sanducc.attendance.config;

import io.github.sanducc.attendance.common.TrustedProxyForwardedHeaderFilter;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.autoconfigure.servlet.ForwardedHeaderFilterCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * With {@code server.forward-headers-strategy=framework} (behind Cloud Run, Caddy, nginx), registers
 * {@link TrustedProxyForwardedHeaderFilter} in place of Spring Boot's plain {@link ForwardedHeaderFilter}. Boot backs
 * off ({@code @ConditionalOnMissingFilterBean(ForwardedHeaderFilter.class)}); the registration below is the one Boot
 * would make (same order, dispatcher types, and Boot's {@link ForwardedHeaderFilterCustomizer}, through which Tomcat
 * applies {@code server.tomcat.use-relative-redirects}), with a different client IP rule (#39).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
public class ForwardedHeadersConfig {

    private static final Logger log = LoggerFactory.getLogger(ForwardedHeadersConfig.class);

    @Bean
    FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter(
            AttendanceProperties properties, ObjectProvider<ForwardedHeaderFilterCustomizer> customizer) {
        int hops = properties.security().trustedProxyHops();
        log.info("Forwarded headers: scheme and host from the proxy, client IP = {}",
                hops == 0 ? "the TCP peer (trusted-proxy-hops 0)"
                        : "X-Forwarded-For entry " + hops + " from the right (trusted-proxy-hops)");
        ForwardedHeaderFilter filter = new TrustedProxyForwardedHeaderFilter(hops);
        customizer.ifAvailable(c -> c.customize(filter));
        FilterRegistrationBean<ForwardedHeaderFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
