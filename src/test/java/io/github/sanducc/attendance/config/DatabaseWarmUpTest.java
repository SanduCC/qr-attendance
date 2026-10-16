package io.github.sanducc.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseWarmUpTest {

    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final DatabaseWarmUp warmUp = new DatabaseWarmUp(destination -> capturingLog());

    @Test
    void offUnlessEnabled() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/attendance");

        assertThat(warmUp.start(environment)).isEmpty();
    }

    @Test
    void onlyForPostgres() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DatabaseWarmUp.ENABLED_PROPERTY, "true")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:attendance");

        assertThat(warmUp.start(environment)).isEmpty();
    }

    @Test
    void anUnreachableDatabaseIsOnlyLogged() throws InterruptedException {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DatabaseWarmUp.ENABLED_PROPERTY, "true")
                // Port 1 on loopback: nothing listens there, so the connection is refused at once.
                .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/attendance?connectTimeout=5")
                .withProperty("spring.datasource.username", "attendance")
                .withProperty("spring.datasource.password", "not-a-real-password");

        Optional<Thread> thread = warmUp.start(environment);

        assertThat(thread).isPresent();
        assertThat(thread.get().isDaemon()).isTrue();
        thread.get().join(30_000);
        assertThat(thread.get().isAlive()).isFalse();
        assertThat(logged).singleElement().asString()
                .startsWith("Database warm-up failed after")
                .contains("startup continues")
                .doesNotContain("not-a-real-password");
    }

    private Log capturingLog() {
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[] {Log.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("info")) {
                        logged.add(String.valueOf(args[0]));
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
    }
}
