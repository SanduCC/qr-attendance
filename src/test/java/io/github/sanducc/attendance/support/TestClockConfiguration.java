package io.github.sanducc.attendance.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Bean overriding is disabled, so instead of replacing {@code ClockConfig.clock()} this adds a
 * differently-named {@code @Primary} Clock that wins every constructor injection of {@code Clock}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }
}
