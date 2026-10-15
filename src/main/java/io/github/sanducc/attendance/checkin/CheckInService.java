package io.github.sanducc.attendance.checkin;

import io.github.sanducc.attendance.common.AuthenticatedUser;
import io.github.sanducc.attendance.common.ClientContext;
import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.common.RateLimiter;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.CourseRepository;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.QrTokenService;
import io.github.sanducc.attendance.session.SessionService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The student check-in rules. Not transactional on purpose: each write is its own short
 * transaction so a unique-constraint violation can be caught and explained to the student.
 */
@Service
public class CheckInService {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private final SessionService sessions;
    private final CourseRepository courses;
    private final QrTokenService qrTokens;
    private final StudentRepository students;
    private final AttendanceRecordRepository records;
    private final PepperedHasher hasher;
    private final RateLimiter rateLimiter;
    private final AttendanceProperties properties;
    private final Clock clock;

    public CheckInService(SessionService sessions, CourseRepository courses, QrTokenService qrTokens,
                          StudentRepository students, AttendanceRecordRepository records, PepperedHasher hasher,
                          RateLimiter rateLimiter, AttendanceProperties properties, Clock clock) {
        this.sessions = sessions;
        this.courses = courses;
        this.qrTokens = qrTokens;
        this.students = students;
        this.records = records;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
    }

    public sealed interface ScanOutcome {
        record Accepted(PendingCheckIn pending, String courseName) implements ScanOutcome {}
        record SessionNotFound() implements ScanOutcome {}
        record SessionClosed() implements ScanOutcome {}
        record TokenExpired() implements ScanOutcome {}
        record RateLimited() implements ScanOutcome {}
    }

    public sealed interface CheckInOutcome {
        record CheckedIn(Student student, Instant at) implements CheckInOutcome {}
        record AlreadyCheckedIn(Student student, Instant at) implements CheckInOutcome {}
        record DeviceAlreadyUsed() implements CheckInOutcome {}
        record ScanExpired() implements CheckInOutcome {}
        record SessionClosed() implements CheckInOutcome {}
        record NameRequired() implements CheckInOutcome {}
        record RateLimited() implements CheckInOutcome {}
    }

    /** Rule 1 + 2: the rotating token is checked here, before sign-in, and bound to this browser. */
    public ScanOutcome scan(UUID sessionId, String token, ClientContext client) {
        if (!withinRateLimit(client)) {
            return new ScanOutcome.RateLimited();
        }
        Optional<AttendanceSession> found = sessions.findForScan(sessionId);
        if (found.isEmpty()) {
            return new ScanOutcome.SessionNotFound();
        }
        AttendanceSession session = found.get();
        Instant now = clock.instant();
        if (!session.isOpenAt(now)) {
            return new ScanOutcome.SessionClosed();
        }
        if (!qrTokens.isValid(sessionId, token)) {
            return new ScanOutcome.TokenExpired();
        }
        return new ScanOutcome.Accepted(new PendingCheckIn(sessionId, now, deviceHash(client)),
                courseName(session));
    }

    /**
     * Records attendance. {@code names} is only used when this account has never registered;
     * after that, names are locked.
     */
    public CheckInOutcome checkIn(UUID sessionId, PendingCheckIn pending, AuthenticatedUser user, NameForm names,
                                  ClientContext client) {
        if (!withinRateLimit(client)) {
            return new CheckInOutcome.RateLimited();
        }
        Optional<Student> existingStudent = findStudent(user);
        if (existingStudent.isPresent()) {
            Optional<AttendanceRecord> existing = records.findBySessionIdAndStudentId(sessionId,
                    existingStudent.get().getId());
            if (existing.isPresent()) {
                return new CheckInOutcome.AlreadyCheckedIn(existingStudent.get(), existing.get().getCheckedInAt());
            }
        }

        String deviceHash = deviceHash(client);
        if (!isPendingUsable(sessionId, pending, deviceHash)) {
            return new CheckInOutcome.ScanExpired();
        }
        // The scan must have happened while the session was open. A teacher closing early moves
        // closes_at to "now", so students who already scanned can still finish within the grace period.
        Optional<AttendanceSession> session = sessions.findForScan(sessionId);
        if (session.isEmpty() || !pending.scannedAt().isBefore(session.get().getClosesAt())) {
            return new CheckInOutcome.SessionClosed();
        }
        // Rule 4, checked up front for a friendly message; the DB constraint is the real guard.
        if (records.existsBySessionIdAndDeviceIdHash(sessionId, deviceHash)) {
            return new CheckInOutcome.DeviceAlreadyUsed();
        }

        Student student;
        if (existingStudent.isPresent()) {
            student = refreshEmail(existingStudent.get(), user);
        } else {
            if (names == null || isBlank(names.getFirstName()) || isBlank(names.getLastName())) {
                return new CheckInOutcome.NameRequired();
            }
            student = register(user, names.normalized());
        }

        Instant now = clock.instant();
        AttendanceRecord record = new AttendanceRecord(sessionId, student.getId(), now, deviceHash,
                hasher.hash("ip", client.ip()), truncate(client.userAgent()));
        try {
            records.saveAndFlush(record);
            return new CheckInOutcome.CheckedIn(student, now);
        } catch (DataIntegrityViolationException e) {
            // Rule 3 + 4 under concurrency: a parallel request won the race.
            Optional<AttendanceRecord> mine = records.findBySessionIdAndStudentId(sessionId, student.getId());
            if (mine.isPresent()) {
                return new CheckInOutcome.AlreadyCheckedIn(student, mine.get().getCheckedInAt());
            }
            if (records.existsBySessionIdAndDeviceIdHash(sessionId, deviceHash)) {
                return new CheckInOutcome.DeviceAlreadyUsed();
            }
            throw e;
        }
    }

    public boolean isPendingUsable(UUID sessionId, PendingCheckIn pending, ClientContext client) {
        return isPendingUsable(sessionId, pending, deviceHash(client));
    }

    public Optional<Student> findStudent(AuthenticatedUser user) {
        return students.findByProviderAndProviderSubject(user.provider(), user.subject());
    }

    public Optional<AttendanceRecord> findRecord(UUID sessionId, Student student) {
        return records.findBySessionIdAndStudentId(sessionId, student.getId());
    }

    public Optional<String> courseName(UUID sessionId) {
        return sessions.findForScan(sessionId).map(this::courseName);
    }

    private boolean isPendingUsable(UUID sessionId, PendingCheckIn pending, String deviceHash) {
        return pending != null
                && pending.sessionId().equals(sessionId)
                && pending.deviceIdHash().equals(deviceHash)
                && clock.instant().isBefore(pending.scannedAt().plus(properties.session().checkInGrace()));
    }

    private Student register(AuthenticatedUser user, NameForm names) {
        try {
            return students.saveAndFlush(new Student(user.provider(), user.subject(), user.email(),
                    names.getFirstName(), names.getLastName(), clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // Same account registering from two tabs at once: keep whichever row won.
            return findStudent(user).orElseThrow(() -> e);
        }
    }

    private Student refreshEmail(Student student, AuthenticatedUser user) {
        if (user.email() != null && !Objects.equals(student.getEmail(), user.email())) {
            student.setEmail(user.email());
            return students.save(student);
        }
        return student;
    }

    private boolean withinRateLimit(ClientContext client) {
        // Evaluate both so each counter sees every attempt.
        boolean ipOk = rateLimiter.tryAcquire("ip:" + hasher.hash("ip", client.ip()),
                properties.rateLimit().perIpPerMinute());
        boolean deviceOk = rateLimiter.tryAcquire("dev:" + deviceHash(client),
                properties.rateLimit().perDevicePerMinute());
        return ipOk && deviceOk;
    }

    private String deviceHash(ClientContext client) {
        return hasher.hash("device", client.deviceId());
    }

    private String courseName(AttendanceSession session) {
        return courses.findById(session.getCourseId()).map(Course::getName).orElse("");
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() <= MAX_USER_AGENT_LENGTH ? userAgent : userAgent.substring(0, MAX_USER_AGENT_LENGTH);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
