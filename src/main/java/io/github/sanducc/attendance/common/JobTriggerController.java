package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.config.JobTriggerSecurityConfig;
import io.github.sanducc.attendance.session.SessionService;
import java.time.Instant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs the daily jobs on request: Cloud Scheduler calls it once a night with a Google OIDC token (who may call it is
 * enforced in {@link JobTriggerSecurityConfig}). Both jobs are idempotent, so retries and duplicate calls are safe.
 */
@RestController
public class JobTriggerController {

    private final RetentionJob retentionJob;
    private final SessionService sessionService;

    public JobTriggerController(RetentionJob retentionJob, SessionService sessionService) {
        this.retentionJob = retentionJob;
        this.sessionService = sessionService;
    }

    /** Counts only, never personal data (the purge count is also logged). */
    public record DailyJobsResult(Instant retentionCutoff, int deletedSessions, int deletedStudents,
                                  int closedSessions) {}

    @PostMapping(JobTriggerSecurityConfig.DAILY_JOBS_PATH)
    public DailyJobsResult runDailyJobs() {
        RetentionJob.Result purge = retentionJob.purge();
        int closed = sessionService.closeExpiredSessions();
        return new DailyJobsResult(purge.cutoff(), purge.deletedSessions(), purge.deletedStudents(), closed);
    }
}
