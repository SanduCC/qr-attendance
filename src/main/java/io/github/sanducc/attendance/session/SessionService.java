package io.github.sanducc.attendance.session;

import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.CourseRepository;
import io.github.sanducc.attendance.course.CourseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final AttendanceSessionRepository sessions;
    private final CourseRepository courses;
    private final CourseService courseService;
    private final QrTokenService qrTokens;
    private final QrSvgRenderer qrRenderer;
    private final AttendanceProperties properties;
    private final Clock clock;

    public SessionService(AttendanceSessionRepository sessions, CourseRepository courses, CourseService courseService,
                          QrTokenService qrTokens, QrSvgRenderer qrRenderer, AttendanceProperties properties,
                          Clock clock) {
        this.sessions = sessions;
        this.courses = courses;
        this.courseService = courseService;
        this.qrTokens = qrTokens;
        this.qrRenderer = qrRenderer;
        this.properties = properties;
        this.clock = clock;
    }

    public record OwnedSession(AttendanceSession session, Course course) {}

    public record DisplayQr(boolean open, String url, String svg, long windowEndsAtMillis, long serverTimeMillis) {}

    @Transactional
    public AttendanceSession openSession(long teacherId, long courseId, Integer durationMinutes,
                                         Integer expectedHeadcount) {
        Course course = courseService.getOwnedCourse(teacherId, courseId);
        Duration duration = durationMinutes == null
                ? properties.session().defaultDuration()
                : Duration.ofMinutes(durationMinutes);
        if (duration.isNegative() || duration.isZero() || duration.compareTo(properties.session().maxDuration()) > 0) {
            throw new IllegalArgumentException("Session duration must be between 1 minute and "
                    + properties.session().maxDuration().toMinutes() + " minutes");
        }
        Instant now = clock.instant();
        return sessions.save(new AttendanceSession(course.getId(), now, now.plus(duration), expectedHeadcount));
    }

    /** Throws NotFound both when the session doesn't exist and when another teacher owns it. */
    @Transactional(readOnly = true)
    public OwnedSession getOwnedSession(long teacherId, UUID sessionId) {
        AttendanceSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        Course course = courses.findByIdAndTeacherId(session.getCourseId(), teacherId)
                .orElseThrow(() -> new NotFoundException("Session not found"));
        return new OwnedSession(session, course);
    }

    @Transactional(readOnly = true)
    public List<AttendanceSession> listSessions(long teacherId, long courseId) {
        Course course = courseService.getOwnedCourse(teacherId, courseId);
        return sessions.findByCourseIdOrderByOpenedAtDesc(course.getId());
    }

    @Transactional
    public void close(long teacherId, UUID sessionId) {
        AttendanceSession session = getOwnedSession(teacherId, sessionId).session();
        session.close(clock.instant());
        sessions.save(session);
    }

    @Transactional(readOnly = true)
    public DisplayQr currentQr(long teacherId, UUID sessionId) {
        AttendanceSession session = getOwnedSession(teacherId, sessionId).session();
        Instant now = clock.instant();
        if (!session.isOpenAt(now)) {
            return new DisplayQr(false, null, null, 0, now.toEpochMilli());
        }
        QrTokenService.QrToken token = qrTokens.current(sessionId);
        String url = properties.publicBaseUrl() + "/a/" + sessionId + "?t=" + token.value();
        return new DisplayQr(true, url, qrRenderer.render(url), token.windowEndsAt().toEpochMilli(),
                now.toEpochMilli());
    }

    /** Public lookup for the scan flow; no ownership check because students don't own sessions. */
    @Transactional(readOnly = true)
    public Optional<AttendanceSession> findForScan(UUID sessionId) {
        return sessions.findById(sessionId);
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    @Transactional
    public void closeExpiredSessions() {
        int closed = sessions.closeExpired(clock.instant());
        if (closed > 0) {
            log.info("Closed {} expired attendance session(s)", closed);
        }
    }
}
