package io.github.sanducc.attendance.checkin;

import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.session.SessionService;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teacher-facing access to check-ins; every call is scoped to a session the teacher owns (another teacher's session
 * is "not found", like an unknown one). Besides listing and removing entries, a teacher can correct a student's name
 * (#5) and add an entry by hand for someone who couldn't scan (#10), also after the session has closed.
 *
 * <p>Logs carry ids only, never names or emails.
 */
@Service
public class AttendanceRecordService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceRecordService.class);

    private final SessionService sessions;
    private final AttendanceRecordRepository records;
    private final StudentRepository students;
    private final Validator validator;
    private final Clock clock;

    public AttendanceRecordService(SessionService sessions, AttendanceRecordRepository records,
                                   StudentRepository students, Validator validator, Clock clock) {
        this.sessions = sessions;
        this.records = records;
        this.students = students;
        this.validator = validator;
        this.clock = clock;
    }

    /** A student the teacher can pick for a manual entry. */
    public record CourseStudent(long id, String firstName, String lastName, String email) {}

    public sealed interface ManualEntryOutcome {
        record Added(long recordId, Instant at) implements ManualEntryOutcome {}
        /** This student (or, without an account, this name) already has an entry in the session. */
        record AlreadyCheckedIn(long recordId, Instant at) implements ManualEntryOutcome {}
    }

    public sealed interface RenameOutcome {
        record Renamed() implements RenameOutcome {}
        /** Another manual entry of this session already carries the new name. */
        record NameTaken() implements RenameOutcome {}
    }

    /** Names that break the confirm-name rules; {@code fieldErrors} maps firstName / lastName to a message. */
    public static final class InvalidNameException extends RuntimeException {

        private final transient Map<String, String> fieldErrors;

        InvalidNameException(Map<String, String> fieldErrors) {
            super("Invalid name: " + fieldErrors.keySet());
            this.fieldErrors = Map.copyOf(fieldErrors);
        }

        public Map<String, String> fieldErrors() {
            return fieldErrors;
        }
    }

    @Transactional(readOnly = true)
    public List<RecordView> listRecords(long teacherId, UUID sessionId) {
        sessions.getOwnedSession(teacherId, sessionId);
        return records.findViewsBySessionId(sessionId);
    }

    @Transactional(readOnly = true)
    public long countRecords(long teacherId, UUID sessionId) {
        sessions.getOwnedSession(teacherId, sessionId);
        return records.countBySessionId(sessionId);
    }

    @Transactional
    public void removeRecord(long teacherId, UUID sessionId, long recordId) {
        sessions.getOwnedSession(teacherId, sessionId);
        AttendanceRecord record = records.findByIdAndSessionId(recordId, sessionId)
                .orElseThrow(() -> new NotFoundException("Record not found"));
        records.delete(record);
        log.info("Teacher {} removed record {} (manual: {}) from session {}",
                teacherId, recordId, record.isManual(), sessionId);
    }

    /** Students of this session's course (anyone who attended one of its sessions) not yet in this session. */
    @Transactional(readOnly = true)
    public List<CourseStudent> listCourseStudents(long teacherId, UUID sessionId) {
        OwnedSession owned = sessions.getOwnedSession(teacherId, sessionId);
        return students.findCourseStudentsNotIn(owned.course().getId(), sessionId).stream()
                .map(s -> new CourseStudent(s.getId(), s.getFirstName(), s.getLastName(), s.getEmail()))
                .toList();
    }

    /**
     * Corrects the name shown for an entry of the teacher's own session. For a student this changes the
     * {@link Student} itself, so the new name shows in every session and export; for a manual entry without an
     * account it changes that entry only. Not transactional so a unique-name clash can be reported.
     *
     * @throws InvalidNameException if the names break the confirm-name rules
     */
    public RenameOutcome renameEntry(long teacherId, UUID sessionId, long recordId, NameForm names) {
        NameForm valid = validated(names);
        sessions.getOwnedSession(teacherId, sessionId);
        AttendanceRecord record = records.findByIdAndSessionId(recordId, sessionId)
                .orElseThrow(() -> new NotFoundException("Record not found"));
        if (record.getStudentId() != null) {
            Student student = students.findById(record.getStudentId())
                    .orElseThrow(() -> new NotFoundException("Record not found"));
            student.rename(valid.getFirstName(), valid.getLastName());
            students.saveAndFlush(student);
            log.info("Teacher {} renamed student {} via record {} of session {}",
                    teacherId, student.getId(), recordId, sessionId);
            return new RenameOutcome.Renamed();
        }
        record.renameManualEntry(valid.getFirstName(), valid.getLastName());
        try {
            records.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            return new RenameOutcome.NameTaken();
        }
        log.info("Teacher {} renamed manual record {} of session {}", teacherId, recordId, sessionId);
        return new RenameOutcome.Renamed();
    }

    /**
     * Adds a manual entry for one of the course's students (see {@link #listCourseStudents}). Allowed while the
     * session is open and after it closed. Uses the same UNIQUE(session_id, student_id) as a real check-in, so a
     * student can't end up in a session twice, whichever came first.
     */
    public ManualEntryOutcome addManualEntry(long teacherId, UUID sessionId, long studentId) {
        OwnedSession owned = sessions.getOwnedSession(teacherId, sessionId);
        // Only the course's own students: a teacher can't pull in (or probe for) anyone else's.
        if (students.countEntriesInCourse(studentId, owned.course().getId()) == 0) {
            throw new NotFoundException("Student not found");
        }
        Optional<AttendanceRecord> existing = records.findBySessionIdAndStudentId(sessionId, studentId);
        if (existing.isPresent()) {
            return alreadyCheckedIn(existing.get());
        }
        Instant now = clock.instant();
        try {
            AttendanceRecord saved = records.saveAndFlush(
                    AttendanceRecord.manualForStudent(sessionId, studentId, teacherId, now));
            log.info("Teacher {} added manual record {} for student {} to session {}",
                    teacherId, saved.getId(), studentId, sessionId);
            return new ManualEntryOutcome.Added(saved.getId(), now);
        } catch (DataIntegrityViolationException e) {
            // The student scanned (or another tab added them) at the same moment.
            return records.findBySessionIdAndStudentId(sessionId, studentId)
                    .map(this::alreadyCheckedIn)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Adds a manual entry for someone without an account (or who can't sign in): no student row, the validated
     * name is kept on the record. The same name can't be added twice to one session (case-insensitive).
     *
     * @throws InvalidNameException if the names break the confirm-name rules
     */
    public ManualEntryOutcome addManualEntry(long teacherId, UUID sessionId, NameForm names) {
        NameForm valid = validated(names);
        sessions.getOwnedSession(teacherId, sessionId);
        Optional<AttendanceRecord> existing = records.findManualByName(sessionId, valid.getFirstName(),
                valid.getLastName());
        if (existing.isPresent()) {
            return alreadyCheckedIn(existing.get());
        }
        Instant now = clock.instant();
        try {
            AttendanceRecord saved = records.saveAndFlush(AttendanceRecord.manualByName(sessionId,
                    valid.getFirstName(), valid.getLastName(), teacherId, now));
            log.info("Teacher {} added manual record {} without an account to session {}",
                    teacherId, saved.getId(), sessionId);
            return new ManualEntryOutcome.Added(saved.getId(), now);
        } catch (DataIntegrityViolationException e) {
            return records.findManualByName(sessionId, valid.getFirstName(), valid.getLastName())
                    .map(this::alreadyCheckedIn)
                    .orElseThrow(() -> e);
        }
    }

    private ManualEntryOutcome alreadyCheckedIn(AttendanceRecord record) {
        return new ManualEntryOutcome.AlreadyCheckedIn(record.getId(), record.getCheckedInAt());
    }

    /** The confirm-name form's rules (same annotations), then the same trimming; throws on any violation. */
    private NameForm validated(NameForm names) {
        NameForm form = names == null ? new NameForm() : names;
        Map<String, String> errors = new TreeMap<>();
        for (ConstraintViolation<NameForm> violation : validator.validate(form)) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        if (!errors.isEmpty()) {
            throw new InvalidNameException(errors);
        }
        return form.normalized();
    }
}
