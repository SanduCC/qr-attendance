package io.github.sanducc.attendance.session;

import io.github.sanducc.attendance.checkin.AttendanceRecordRepository;
import io.github.sanducc.attendance.checkin.SessionCount;
import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.CourseRepository;
import io.github.sanducc.attendance.course.CourseService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final AttendanceSessionRepository sessions;
    private final AttendanceRecordRepository records;
    private final CourseRepository courses;
    private final CourseService courseService;
    private final QrTokenService qrTokens;
    private final QrSvgRenderer qrRenderer;
    private final AttendanceProperties properties;
    private final Clock clock;

    public SessionService(AttendanceSessionRepository sessions, AttendanceRecordRepository records,
                          CourseRepository courses, CourseService courseService, QrTokenService qrTokens,
                          QrSvgRenderer qrRenderer, AttendanceProperties properties, Clock clock) {
        this.sessions = sessions;
        this.records = records;
        this.courses = courses;
        this.courseService = courseService;
        this.qrTokens = qrTokens;
        this.qrRenderer = qrRenderer;
        this.properties = properties;
        this.clock = clock;
    }

    public record OwnedSession(AttendanceSession session, Course course) {}

    public record DisplayQr(boolean open, String url, String svg, long windowEndsAtMillis, long serverTimeMillis) {}

    /** A session plus its number of check-ins. */
    public record SessionSummary(AttendanceSession session, long checkedIn) {}

    /** A course plus its most recent session ({@code lastSession} is null when it has none yet). */
    public record CourseSummary(Course course, SessionSummary lastSession) {

        /** What the next one-click "Start attendance" uses: the last session's expected headcount. */
        public Integer lastExpectedHeadcount() {
            return lastSession == null ? null : lastSession.session().getExpectedHeadcount();
        }
    }

    /**
     * "Start attendance": a null duration means the configured default; a null expected headcount means "same as this
     * course's most recent session", unless {@code headcountExplicit} says the teacher deliberately left it empty.
     */
    @Transactional
    public AttendanceSession startSession(long teacherId, long courseId, Integer durationMinutes,
                                          Integer expectedHeadcount, boolean headcountExplicit) {
        Integer expected = expectedHeadcount == null && !headcountExplicit
                ? lastExpectedHeadcount(teacherId, courseId)
                : expectedHeadcount;
        return openSession(teacherId, courseId, durationMinutes, expected);
    }

    /** The expected headcount of the most recent session of the teacher's own course; null if none or none set. */
    @Transactional(readOnly = true)
    public Integer lastExpectedHeadcount(long teacherId, long courseId) {
        Course course = courseService.getOwnedCourse(teacherId, courseId);
        return sessions.findFirstByCourseIdOrderByOpenedAtDesc(course.getId())
                .map(AttendanceSession::getExpectedHeadcount)
                .orElse(null);
    }

    /** Opens a session with exactly these values (null duration = configured default, null headcount = none). */
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

    /** The teacher's courses (by name), each with its most recent session and that session's check-in count. */
    @Transactional(readOnly = true)
    public List<CourseSummary> listCourseSummaries(long teacherId) {
        List<Course> owned = courseService.listCourses(teacherId);
        if (owned.isEmpty()) {
            return List.of();
        }
        // The course ids come from the teacher-scoped list, so every session found here is the teacher's own.
        Map<Long, AttendanceSession> latest = new HashMap<>();
        for (AttendanceSession s : sessions.findLatestByCourseIds(owned.stream().map(Course::getId).toList())) {
            latest.putIfAbsent(s.getCourseId(), s);
        }
        Map<UUID, Long> counts = countCheckIns(latest.values().stream().map(AttendanceSession::getId).toList());
        return owned.stream()
                .map(c -> {
                    AttendanceSession last = latest.get(c.getId());
                    return new CourseSummary(c, last == null ? null
                            : new SessionSummary(last, counts.getOrDefault(last.getId(), 0L)));
                })
                .toList();
    }

    /** All sessions of the teacher's own course, newest first, with their check-in counts. */
    @Transactional(readOnly = true)
    public List<SessionSummary> listSessionSummaries(long teacherId, long courseId) {
        List<AttendanceSession> list = listSessions(teacherId, courseId);
        Map<UUID, Long> counts = countCheckIns(list.stream().map(AttendanceSession::getId).toList());
        return list.stream()
                .map(s -> new SessionSummary(s, counts.getOrDefault(s.getId(), 0L)))
                .toList();
    }

    private Map<UUID, Long> countCheckIns(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return records.countBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(SessionCount::sessionId, SessionCount::checkedIn));
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
