package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.checkin.StudentRepository;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.session.AttendanceSessionRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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

    @Scheduled(cron = "${attendance.retention.cron}", zone = "${attendance.display-zone}")
    @Transactional
    public void purge() {
        Instant cutoff = RetentionPolicy.purgeCutoff(clock.instant(), properties.displayZone(),
                properties.retention().academicYearEndDay(), properties.retention().graceDays());
        int deletedSessions = sessions.deleteOpenedBefore(cutoff);
        int deletedStudents = students.deleteWithoutRecords();
        if (deletedSessions > 0 || deletedStudents > 0) {
            log.info("Retention purge before {}: {} session(s), {} student(s) deleted",
                    cutoff, deletedSessions, deletedStudents);
        }
    }
}
