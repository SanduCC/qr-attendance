package io.github.sanducc.attendance.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Optional;
import java.util.Properties;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Opens one throwaway database connection in a background thread as soon as the configuration is known, i.e.
 * before the Spring context starts (issue #28).
 *
 * <p>On Cloud Run with min-instances 0, a cold start usually meets a suspended Neon compute too: the first
 * connection then waits for Neon to wake up (and pays the cross-region TLS handshake and the JDBC driver's class
 * loading) while the main thread stands still in Flyway. Started here, that wait overlaps with the seconds the
 * context needs before it touches the database, so Flyway's connection finds an awake database. The connection is
 * closed right away; the pool opens its own. Failures are only logged: Flyway reports a database that is really
 * unreachable a moment later, with the usual error.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Off with {@code attendance.startup.warm-up-database=false}
 * (tests, and the image's AOT training run, which has no database).
 */
public class DatabaseWarmUp implements EnvironmentPostProcessor, Ordered {

    static final String ENABLED_PROPERTY = "attendance.startup.warm-up-database";

    private final Log log;

    public DatabaseWarmUp(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DatabaseWarmUp.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        start(environment);
    }

    /** Starts the warm-up thread if enabled and the datasource is PostgreSQL; returns it (for tests). */
    Optional<Thread> start(ConfigurableEnvironment environment) {
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
            return Optional.empty();
        }
        String url = environment.getProperty("spring.datasource.url");
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        putIfSet(properties, "user", environment.getProperty("spring.datasource.username"));
        putIfSet(properties, "password", environment.getProperty("spring.datasource.password"));
        properties.setProperty("ApplicationName", "qr-attendance warm-up");
        // Defaults for the driver; parameters in the URL take precedence.
        properties.setProperty("connectTimeout", "20");
        properties.setProperty("loginTimeout", "30");
        return Optional.of(Thread.ofPlatform().daemon().name("db-warm-up").start(() -> connectOnce(url, properties)));
    }

    private void connectOnce(String url, Properties properties) {
        long start = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(url, properties)) {
            log.info("Database warm-up: connected in " + elapsedMillis(start) + " ms");
        } catch (Exception e) {
            log.info("Database warm-up failed after " + elapsedMillis(start) + " ms, startup continues: " + e);
        }
    }

    private static void putIfSet(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** After the config files and env vars have been loaded, so the datasource properties are final. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
