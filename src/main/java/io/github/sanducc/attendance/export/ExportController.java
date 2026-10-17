package io.github.sanducc.attendance.export;

import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.course.TeacherService;
import io.github.sanducc.attendance.session.SessionService;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import io.github.sanducc.attendance.stats.CourseStatsService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ExportController {

    private final TeacherService teachers;
    private final SessionService sessions;
    private final AttendanceRecordService records;
    private final CourseStatsService stats;
    private final DisplayTime displayTime;
    private final Clock clock;

    public ExportController(TeacherService teachers, SessionService sessions, AttendanceRecordService records,
                            CourseStatsService stats, DisplayTime displayTime, Clock clock) {
        this.teachers = teachers;
        this.sessions = sessions;
        this.records = records;
        this.stats = stats;
        this.displayTime = displayTime;
        this.clock = clock;
    }

    /** Per-student stats of the teacher's own course (#8); someone else's course is a 404. */
    @GetMapping("/teacher/courses/{courseId}/stats.xlsx")
    public ResponseEntity<byte[]> exportStatsXlsx(@PathVariable long courseId,
                                                  OAuth2AuthenticationToken authentication) throws IOException {
        StatsSheet sheet = statsSheet(courseId, authentication);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxExporter.write(out, sheet);
        return attachment(out.toByteArray(), XlsxExporter.CONTENT_TYPE, sheet.filename("xlsx"));
    }

    @GetMapping("/teacher/courses/{courseId}/stats.csv")
    public ResponseEntity<byte[]> exportStatsCsv(@PathVariable long courseId,
                                                 OAuth2AuthenticationToken authentication) throws IOException {
        StatsSheet sheet = statsSheet(courseId, authentication);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        CsvExporter.write(writer, sheet);
        return attachment(out.toByteArray(), CsvExporter.CONTENT_TYPE, sheet.filename("csv"));
    }

    private StatsSheet statsSheet(long courseId, OAuth2AuthenticationToken authentication) {
        long teacherId = teachers.requireTeacherId(authentication);
        return StatsSheet.of(stats.courseStats(teacherId, courseId), displayTime.zone(),
                LocalDate.now(clock.withZone(displayTime.zone())));
    }

    @GetMapping("/sessions/{sessionId}/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication)
            throws IOException {
        AttendanceSheet sheet = sheet(sessionId, authentication);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxExporter.write(out, sheet);
        return attachment(out.toByteArray(), XlsxExporter.CONTENT_TYPE, sheet.filename("xlsx"));
    }

    @GetMapping("/sessions/{sessionId}/export.csv")
    public ResponseEntity<byte[]> exportCsv(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication)
            throws IOException {
        AttendanceSheet sheet = sheet(sessionId, authentication);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        CsvExporter.write(writer, sheet);
        return attachment(out.toByteArray(), CsvExporter.CONTENT_TYPE, sheet.filename("csv"));
    }

    private AttendanceSheet sheet(UUID sessionId, OAuth2AuthenticationToken authentication) {
        long teacherId = teachers.requireTeacherId(authentication);
        OwnedSession owned = sessions.getOwnedSession(teacherId, sessionId);
        return AttendanceSheet.of(owned, records.listRecords(teacherId, sessionId), displayTime.zone());
    }

    private static ResponseEntity<byte[]> attachment(byte[] body, String contentType, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(body);
    }
}
