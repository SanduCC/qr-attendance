package io.github.sanducc.attendance.session;

import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.checkin.AttendanceRecordService.CourseStudent;
import io.github.sanducc.attendance.checkin.AttendanceRecordService.InvalidNameException;
import io.github.sanducc.attendance.checkin.AttendanceRecordService.ManualEntryOutcome;
import io.github.sanducc.attendance.checkin.AttendanceRecordService.RenameOutcome;
import io.github.sanducc.attendance.checkin.NameForm;
import io.github.sanducc.attendance.checkin.RecordView;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.course.TeacherService;
import io.github.sanducc.attendance.session.SessionService.DisplayQr;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Teacher projector page plus the JSON endpoints it polls, and the teacher's fixes to the list: remove an entry,
 * correct a name (#5), add an entry by hand (#10). The page sends those with the CSRF header.
 */
@Controller
@RequestMapping("/sessions/{sessionId}")
public class SessionController {

    private final TeacherService teachers;
    private final SessionService sessions;
    private final AttendanceRecordService records;
    private final DisplayTime displayTime;
    private final Clock clock;

    public SessionController(TeacherService teachers, SessionService sessions, AttendanceRecordService records,
                             DisplayTime displayTime, Clock clock) {
        this.teachers = teachers;
        this.sessions = sessions;
        this.records = records;
        this.displayTime = displayTime;
        this.clock = clock;
    }

    /**
     * One table row. {@code manual}: added by the teacher, not scanned. {@code registered}: the entry belongs to a
     * student account, so a name correction applies to that student everywhere (otherwise to this entry only).
     */
    public record RecordRow(Long id, String firstName, String lastName, String email, String checkedInAt,
                            boolean manual, boolean registered) {}

    /**
     * {@code closesAtMillis} and {@code serverTimeMillis} let the projector run its "Closes in mm:ss" countdown on
     * server time, whatever the projector laptop's clock says.
     */
    public record RecordsResponse(boolean open, String closesAt, long closesAtMillis, long serverTimeMillis,
                                  long checkedIn, Integer expectedHeadcount, boolean overHeadcount,
                                  List<RecordRow> records) {}

    /** Error body of the add / rename calls: a message, plus per-field messages (firstName, lastName) if any. */
    public record ErrorResponse(String message, Map<String, String> fieldErrors) {}

    /** A manual entry that was added. */
    public record AddedResponse(long recordId, String checkedInAt) {}

    @GetMapping("/display")
    public String display(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication, Model model) {
        OwnedSession owned = sessions.getOwnedSession(teachers.requireTeacherId(authentication), sessionId);
        AttendanceSession session = owned.session();
        Instant now = clock.instant();
        model.addAttribute("course", owned.course());
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("open", session.isOpenAt(now));
        model.addAttribute("expectedHeadcount", session.getExpectedHeadcount());
        model.addAttribute("openedDay", displayTime.day(session.getOpenedAt()));
        model.addAttribute("opensAt", displayTime.time(session.getOpenedAt()));
        model.addAttribute("closesAt", displayTime.time(session.getClosesAt()));
        model.addAttribute("closesAtMillis", session.getClosesAt().toEpochMilli());
        model.addAttribute("serverTimeMillis", now.toEpochMilli());
        return "teacher/display";
    }

    @GetMapping(value = "/qr-token", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DisplayQr qrToken(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication) {
        return sessions.currentQr(teachers.requireTeacherId(authentication), sessionId);
    }

    @GetMapping(value = "/records", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public RecordsResponse records(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication) {
        long teacherId = teachers.requireTeacherId(authentication);
        AttendanceSession session = sessions.getOwnedSession(teacherId, sessionId).session();
        List<RecordView> views = records.listRecords(teacherId, sessionId);
        List<RecordRow> rows = views.stream()
                .map(r -> new RecordRow(r.id(), r.firstName(), r.lastName(), r.email(),
                        displayTime.timeWithSeconds(r.checkedInAt()), r.manual(), r.studentId() != null))
                .toList();
        Integer expected = session.getExpectedHeadcount();
        // Rule 5: flag more check-ins than people the teacher expects in the room. Manual entries count too: they
        // are people the teacher says are in the room.
        boolean over = expected != null && rows.size() > expected;
        Instant now = clock.instant();
        return new RecordsResponse(session.isOpenAt(now), displayTime.time(session.getClosesAt()),
                session.getClosesAt().toEpochMilli(), now.toEpochMilli(), rows.size(), expected, over, rows);
    }

    @DeleteMapping("/records/{recordId}")
    public ResponseEntity<Void> removeRecord(@PathVariable UUID sessionId, @PathVariable long recordId,
                                             OAuth2AuthenticationToken authentication) {
        records.removeRecord(teachers.requireTeacherId(authentication), sessionId, recordId);
        return ResponseEntity.noContent().build();
    }

    /** The course's students not yet in this session, for the "Add entry" picker. */
    @GetMapping(value = "/students", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<CourseStudent> courseStudents(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication) {
        return records.listCourseStudents(teachers.requireTeacherId(authentication), sessionId);
    }

    /**
     * Manual entry: {@code studentId} picks one of the course's students; without it, {@code firstName} and
     * {@code lastName} add someone who has no account. 201, 400 with field errors, or 409 if already in the session.
     */
    @PostMapping(value = "/records", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Object> addRecord(@PathVariable UUID sessionId,
                                            @RequestParam(required = false) Long studentId,
                                            @RequestParam(required = false) String firstName,
                                            @RequestParam(required = false) String lastName,
                                            OAuth2AuthenticationToken authentication) {
        long teacherId = teachers.requireTeacherId(authentication);
        ManualEntryOutcome outcome = studentId != null
                ? records.addManualEntry(teacherId, sessionId, studentId)
                : records.addManualEntry(teacherId, sessionId, new NameForm(firstName, lastName));
        return switch (outcome) {
            case ManualEntryOutcome.Added added -> ResponseEntity.status(HttpStatus.CREATED)
                    .body(new AddedResponse(added.recordId(), displayTime.timeWithSeconds(added.at())));
            case ManualEntryOutcome.AlreadyCheckedIn already -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("Already checked in at " + displayTime.time(already.at()), Map.of()));
        };
    }

    /** Corrects the name of an entry: 204, 400 with field errors, or 409 if a manual entry already has that name. */
    @PostMapping(value = "/records/{recordId}/name", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Object> renameRecord(@PathVariable UUID sessionId, @PathVariable long recordId,
                                               @RequestParam(required = false) String firstName,
                                               @RequestParam(required = false) String lastName,
                                               OAuth2AuthenticationToken authentication) {
        RenameOutcome outcome = records.renameEntry(teachers.requireTeacherId(authentication), sessionId, recordId,
                new NameForm(firstName, lastName));
        return switch (outcome) {
            case RenameOutcome.Renamed _ -> ResponseEntity.noContent().build();
            case RenameOutcome.NameTaken _ -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorResponse("Someone with this name is already in this session", Map.of()));
        };
    }

    @ExceptionHandler(InvalidNameException.class)
    @ResponseBody
    public ResponseEntity<ErrorResponse> invalidName(InvalidNameException e) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse("Check the name", e.fieldErrors()));
    }

    @PostMapping("/close")
    public String close(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication) {
        sessions.close(teachers.requireTeacherId(authentication), sessionId);
        return "redirect:/sessions/" + sessionId + "/display";
    }
}
