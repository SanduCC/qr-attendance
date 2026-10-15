package io.github.sanducc.attendance.export;

import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.checkin.RecordView;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.course.TeacherService;
import io.github.sanducc.attendance.session.SessionService;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ExportController {

    private final TeacherService teachers;
    private final SessionService sessions;
    private final AttendanceRecordService records;
    private final DisplayTime displayTime;

    public ExportController(TeacherService teachers, SessionService sessions, AttendanceRecordService records,
                            DisplayTime displayTime) {
        this.teachers = teachers;
        this.sessions = sessions;
        this.records = records;
        this.displayTime = displayTime;
    }

    @GetMapping("/sessions/{sessionId}/export.csv")
    public void export(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication,
                       HttpServletResponse response) throws IOException {
        long teacherId = teachers.requireTeacherId(authentication);
        OwnedSession owned = sessions.getOwnedSession(teacherId, sessionId);
        List<RecordView> rows = records.listRecords(teacherId, sessionId);

        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(displayTime.zone())
                .format(owned.session().getOpenedAt());
        String filename = owned.course().getCode().replaceAll("[^A-Za-z0-9_-]", "_") + "_" + date + ".csv";
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
        Writer writer = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8);
        CsvExporter.write(writer, rows, displayTime.zone());
    }
}
