package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.session.SessionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-app timers for the background jobs, for hosts where the app runs all the time (docker compose, a VPS). On
 * Cloud Run (scale to zero, CPU only during requests) they don't fire reliably: set
 * {@code JOBS_IN_APP_SCHEDULE=false} there and let Cloud Scheduler call {@code POST /internal/jobs/daily}.
 */
@Component
@ConditionalOnProperty(name = "attendance.jobs.in-app-schedule", havingValue = "true", matchIfMissing = true)
public class InAppJobScheduler {

    private final RetentionJob retentionJob;
    private final SessionService sessionService;

    public InAppJobScheduler(RetentionJob retentionJob, SessionService sessionService) {
        this.retentionJob = retentionJob;
        this.sessionService = sessionService;
    }

    @Scheduled(cron = "${attendance.retention.cron}", zone = "${attendance.display-zone}")
    public void purgeExpiredData() {
        retentionJob.purge();
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void closeExpiredSessions() {
        sessionService.closeExpiredSessions();
    }
}
