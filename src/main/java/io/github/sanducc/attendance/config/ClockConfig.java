package io.github.sanducc.attendance.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /** Injected everywhere "now" matters so tests can move time (token windows, grace period). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
