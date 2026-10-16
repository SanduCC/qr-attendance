package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.sanducc.attendance.session.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The in-app timers are on by default (docker compose / VPS) and can be switched off (Cloud Run). */
class InAppJobSchedulerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RetentionJob.class, () -> mock(RetentionJob.class))
            .withBean(SessionService.class, () -> mock(SessionService.class))
            .withUserConfiguration(InAppJobScheduler.class);

    @Test
    void onByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(InAppJobScheduler.class));
    }

    @Test
    void onWhenEnabled() {
        runner.withPropertyValues("attendance.jobs.in-app-schedule=true")
                .run(context -> assertThat(context).hasSingleBean(InAppJobScheduler.class));
    }

    @Test
    void offWhenDisabled() {
        runner.withPropertyValues("attendance.jobs.in-app-schedule=false")
                .run(context -> assertThat(context).doesNotHaveBean(InAppJobScheduler.class));
    }

    @Test
    void timersCallTheJobs() {
        RetentionJob retention = mock(RetentionJob.class);
        SessionService sessions = mock(SessionService.class);
        InAppJobScheduler scheduler = new InAppJobScheduler(retention, sessions);

        scheduler.purgeExpiredData();
        scheduler.closeExpiredSessions();

        verify(retention).purge();
        verify(sessions).closeExpiredSessions();
    }
}
