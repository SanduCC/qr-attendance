package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.checkin.StudentRepository;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.session.AttendanceSessionRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * GDPR retention purge (see {@link RetentionPolicy}). Idempotent: DELETEs with a cutoff that only moves once a year.
 * Triggered by {@link InAppJobScheduler} (docker compose / VPS) or {@code POST /internal/jobs/daily} (Cloud
 * Scheduler on Cloud Run).
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final AttendanceSessionRepository sessions;
    private final StudentRepository students;
    private final AttendanceProperties properties;
    private final Clock clock;

    public RetentionJob(AttendanceSessionRepository sessions, StudentRepository students,
                        AttendanceProperties properties, Clock clock) {
        this.sessions = sessions;
        this.students = students;
        this.properties = properties;
        this.clock = clock;
    }

    /** What one purge did. Counts only, never personal data. */
    public record Result(Instant cutoff, int deletedSessions, int deletedStudents) {}

    /** Deletes sessions (and, by cascade, their records) opened before the cutoff, then students left without records. */
    @Transactional
    public Result purge() {
        Instant cutoff = RetentionPolicy.purgeCutoff(clock.instant(), properties.displayZone(),
                properties.retention().academicYearEndDay(), properties.retention().graceDays());
        int deletedSessions = sessions.deleteOpenedBefore(cutoff);
        int deletedStudents = students.deleteWithoutRecords();
        // One line per run, also when nothing was due: the log is the evidence that the purge ran.
        log.info("Retention purge: cutoff {}, {} session(s) and {} student(s) deleted",
                cutoff, deletedSessions, deletedStudents);
        return new Result(cutoff, deletedSessions, deletedStudents);
    }
}
