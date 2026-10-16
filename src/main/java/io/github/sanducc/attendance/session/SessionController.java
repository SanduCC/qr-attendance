package io.github.sanducc.attendance.session;

import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.checkin.RecordView;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.course.TeacherService;
import io.github.sanducc.attendance.session.SessionService.DisplayQr;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Teacher projector page plus the JSON endpoints it polls. */
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

    public record RecordRow(Long id, String firstName, String lastName, String email, String checkedInAt) {}

    /**
     * {@code closesAtMillis} and {@code serverTimeMillis} let the projector run its "Closes in mm:ss" countdown on
     * server time, whatever the projector laptop's clock says.
     */
    public record RecordsResponse(boolean open, String closesAt, long closesAtMillis, long serverTimeMillis,
                                  long checkedIn, Integer expectedHeadcount, boolean overHeadcount,
                                  List<RecordRow> records) {}

    @GetMapping("/display")
    public String display(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication, Model model) {
        OwnedSession owned = sessions.getOwnedSession(teachers.requireTeacherId(authentication), sessionId);
        AttendanceSession session = owned.session();
        Instant now = clock.instant();
        model.addAttribute("course", owned.course());
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("open", session.isOpenAt(now));
        model.addAttribute("expectedHeadcount", session.getExpectedHeadcount());
        Geofence geofence = session.getGeofence();
        model.addAttribute("geofenceRadiusM", geofence == null ? null : geofence.radiusMeters());
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
                        displayTime.timeWithSeconds(r.checkedInAt())))
                .toList();
        Integer expected = session.getExpectedHeadcount();
        // Rule 5: flag more check-ins than people the teacher expects in the room.
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

    @PostMapping("/close")
    public String close(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication) {
        sessions.close(teachers.requireTeacherId(authentication), sessionId);
        return "redirect:/sessions/" + sessionId + "/display";
    }
}
