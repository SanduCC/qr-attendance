package io.github.sanducc.attendance.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.checkin.NameForm;
import io.github.sanducc.attendance.checkin.Student;
import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.export.XlsxExporterTest;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** #8: per-student attendance stats per course, on a page and as Excel / CSV. */
@ExtendWith(OutputCaptureExtension.class)
class CourseStatsTest extends IntegrationTestSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Duration WEEK = Duration.ofDays(7);

    @Autowired CourseStatsService statsService;
    @Autowired AttendanceRecordService recordService;

    /**
     * Four weekly sessions of Algoritmi (CS101), 2026-10-16 to 2026-11-06:
     * <ol>
     *   <li>closed: Ana, Ștefan and Ion scan;</li>
     *   <li>closed: Ana scans, Ion is added by hand (picked student), Carmen Dobre (no account) is typed in;</li>
     *   <li>closed: nobody;</li>
     *   <li>still open: Ana scans, Carmen Dobre is typed in again.</li>
     * </ol>
     */
    private record Course4(Fixture f, Student ana, Student stefan, Student ion, List<UUID> sessions) {}

    private Course4 fourSessions() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        Student stefan = insertStudent("sub-stefan", "Ștefan", "Țărănescu");
        Student ion = insertStudent("sub-ion", "Ion", "Șerban");
        UUID s1 = f.sessionId();
        insertRecord(s1, ana);
        insertRecord(s1, stefan);
        insertRecord(s1, ion);
        sessionService.close(f.teacherId(), s1);

        clock.advance(WEEK);
        UUID s2 = openSession(f);
        insertRecord(s2, ana);
        recordService.addManualEntry(f.teacherId(), s2, ion.getId());
        recordService.addManualEntry(f.teacherId(), s2, new NameForm("Carmen", "Dobre"));
        sessionService.close(f.teacherId(), s2);

        clock.advance(WEEK);
        UUID s3 = openSession(f);
        sessionService.close(f.teacherId(), s3);

        clock.advance(WEEK);
        UUID s4 = openSession(f);
        insertRecord(s4, ana);
        recordService.addManualEntry(f.teacherId(), s4, new NameForm("Carmen", "Dobre"));
        return new Course4(f, ana, stefan, ion, List.of(s1, s2, s3, s4));
    }

    // ------------------------------------------------------------------ counting rules

    @Test
    void countsEverySessionAndEachPersonsEntries() {
        Course4 c = fourSessions();

        CourseStats stats = statsService.courseStats(c.f().teacherId(), c.f().courseId());

        assertThat(stats.totalSessions()).as("closed, empty and open sessions all count").isEqualTo(4);
        assertThat(stats.sessionsWithoutEntries()).isEqualTo(1);
        assertThat(stats.firstSessionAt()).isEqualTo(Instant.parse("2026-10-16T06:00:05Z"));
        assertThat(stats.lastSessionAt()).isEqualTo(Instant.parse("2026-11-06T06:00:05Z"));
        assertThat(stats.attendees())
                .extracting(AttendeeStats::studentId, AttendeeStats::lastName, AttendeeStats::firstName,
                        AttendeeStats::email, AttendeeStats::attended, AttendeeStats::lastAttendedSessionAt)
                .containsExactly(
                        tuple(null, "Dobre", "Carmen", null, 2L, Instant.parse("2026-11-06T06:00:05Z")),
                        tuple(c.ana().getId(), "Popescu", "Ana", "sub-ana@student.example.ro", 3L,
                                Instant.parse("2026-11-06T06:00:05Z")),
                        tuple(c.ion().getId(), "Șerban", "Ion", "sub-ion@student.example.ro", 2L,
                                Instant.parse("2026-10-23T06:00:05Z")),
                        tuple(c.stefan().getId(), "Țărănescu", "Ștefan", "sub-stefan@student.example.ro", 1L,
                                Instant.parse("2026-10-16T06:00:05Z")));
        assertThat(stats.attendees()).extracting(a -> a.percentOf(stats.totalSessions()))
                .containsExactly(50, 75, 50, 25);
        assertThat(stats.typedNameCount()).isEqualTo(1);
    }

    @Test
    void lastAttendedIsTheSessionDateEvenForAManualEntryAddedLater() {
        Fixture f = openFixture();
        Student ana = insertStudent("sub-ana", "Ana", "Popescu");
        clock.advance(Duration.ofDays(3));
        // Added three days after the session opened: the stats still say the session's date.
        recordService.addManualEntry(f.teacherId(), f.sessionId(), new NameForm("Carmen", "Dobre"));
        insertRecord(openSession(f), ana);

        CourseStats stats = statsService.courseStats(f.teacherId(), f.courseId());

        assertThat(stats.attendees()).extracting(AttendeeStats::lastName, AttendeeStats::lastAttendedSessionAt)
                .containsExactly(
                        tuple("Dobre", Instant.parse("2026-10-16T06:00:05Z")),
                        tuple("Popescu", Instant.parse("2026-10-19T06:00:05Z")));
    }

    @Test
    void typedNamesAreOnePersonPerNameIgnoringCase() {
        Fixture f = openFixture();
        recordService.addManualEntry(f.teacherId(), f.sessionId(), new NameForm("Carmen", "Dobre"));
        recordService.addManualEntry(f.teacherId(), f.sessionId(), new NameForm("Carmen", "Dobrescu"));
        clock.advance(WEEK);
        UUID second = openSession(f);
        recordService.addManualEntry(f.teacherId(), second, new NameForm("CARMEN", "DOBRE"));
        // A student with the same name is a different person: students are keyed on their account.
        insertRecord(second, insertStudent("sub-carmen", "Carmen", "Dobre"));

        CourseStats stats = statsService.courseStats(f.teacherId(), f.courseId());

        assertThat(stats.attendees()).hasSize(3);
        AttendeeStats dobre = stats.attendees().stream()
                .filter(a -> a.typedName() && a.lastName().equalsIgnoreCase("Dobre")).findFirst().orElseThrow();
        assertThat(dobre.attended()).isEqualTo(2);
        assertThat(stats.attendees()).filteredOn(a -> !a.typedName())
                .extracting(AttendeeStats::lastName, AttendeeStats::attended)
                .containsExactly(tuple("Dobre", 1L));
        assertThat(stats.typedNameCount()).isEqualTo(2);
    }

    @Test
    void retentionPurgedSessionsAreSimplyGone() {
        Course4 c = fourSessions();
        jdbc.update("delete from attendance_session where id = ?", c.sessions().getFirst());

        CourseStats stats = statsService.courseStats(c.f().teacherId(), c.f().courseId());

        assertThat(stats.totalSessions()).isEqualTo(3);
        assertThat(stats.attendees()).extracting(AttendeeStats::lastName, AttendeeStats::attended)
                .containsExactly(tuple("Dobre", 2L), tuple("Popescu", 2L), tuple("Șerban", 1L));
    }

    @Test
    void otherCoursesDoNotCount() {
        Course4 c = fourSessions();
        Course other = courseService.createCourse(c.f().teacherId(), "Baze de date", null);
        UUID otherSession = sessionService.openSession(c.f().teacherId(), other.getId(), 15, null).getId();
        insertRecord(otherSession, c.stefan());

        CourseStats stats = statsService.courseStats(c.f().teacherId(), c.f().courseId());

        assertThat(stats.totalSessions()).isEqualTo(4);
        assertThat(stats.attendees()).filteredOn(a -> "Țărănescu".equals(a.lastName()))
                .extracting(AttendeeStats::attended).containsExactly(1L);
        assertThat(statsService.courseStats(c.f().teacherId(), other.getId()).attendees())
                .extracting(AttendeeStats::lastName).containsExactly("Țărănescu");
    }

    @Test
    void aCourseWithoutSessionsOrCheckInsHasNoRows() throws Exception {
        Teacher teacher = createTeacher("prof.empty@university.example.ro");
        Course course = courseService.createCourse(teacher.getId(), "Algoritmi", "CS101");

        CourseStats none = statsService.courseStats(teacher.getId(), course.getId());
        assertThat(none.totalSessions()).isZero();
        assertThat(none.firstSessionAt()).isNull();
        assertThat(none.attendees()).isEmpty();
        mvc.perform(get("/teacher/courses/{id}/stats", course.getId()).with(teacherLogin(teacher.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No sessions yet")));

        sessionService.openSession(teacher.getId(), course.getId(), 15, null);
        CourseStats empty = statsService.courseStats(teacher.getId(), course.getId());
        assertThat(empty.totalSessions()).isEqualTo(1);
        assertThat(empty.sessionsWithoutEntries()).isEqualTo(1);
        assertThat(empty.attendees()).isEmpty();
        mvc.perform(get("/teacher/courses/{id}/stats.csv", course.getId()).with(teacherLogin(teacher.getEmail())))
                .andExpect(status().isOk())
                .andExpect(content().bytes(("﻿Nr.;Last name;First name;Email;Attended;Sessions;Percentage;"
                        + "Last attended;Source\r\n").getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sortsByTheRomanianAlphabet() {
        Fixture f = openFixture();
        List<String> lastNames = List.of("Țepeș", "Tudor", "Ștefan", "Șerban", "Stan", "Sârbu", "Îonescu",
                "Iordache", "Âtu", "Ăbăcioaie", "Abra");
        for (int i = 0; i < lastNames.size(); i++) {
            insertRecord(f.sessionId(), insertStudent("sub-" + i, "Ana", lastNames.get(i)));
        }

        CourseStats stats = statsService.courseStats(f.teacherId(), f.courseId());

        assertThat(stats.attendees()).extracting(AttendeeStats::lastName).containsExactly(
                "Abra", "Ăbăcioaie", "Âtu", "Iordache", "Îonescu", "Sârbu", "Stan", "Șerban", "Ștefan", "Tudor",
                "Țepeș");
    }

    // ------------------------------------------------------------------ page and exports

    @Test
    void ownerSeesTheStatsPage() throws Exception {
        Course4 c = fourSessions();

        String html = mvc.perform(get("/teacher/courses/{id}/stats", c.f().courseId()).with(owner(c.f())))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/stats"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Cross-Origin-Opener-Policy", "same-origin"))
                .andExpect(header().string("Cross-Origin-Embedder-Policy", "require-corp"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andExpect(header().string("Permissions-Policy", containsString("camera=()")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains("4 sessions (1 without check-ins) · Fri 16 Oct 2026 – Fri 6 Nov 2026");
        assertThat(html).containsSubsequence("Dobre Carmen", "manual", "2 / 4", "50%",
                "Popescu Ana", "sub-ana@student.example.ro", "3 / 4", "75%", "Fri 6 Nov 2026",
                "Șerban Ion", "2 / 4", "50%", "Fri 23 Oct 2026",
                "Țărănescu Ștefan", "1 / 4", "25%", "Fri 16 Oct 2026");
        assertThat(html).contains("/teacher/courses/" + c.f().courseId() + "/stats.xlsx",
                "/teacher/courses/" + c.f().courseId() + "/stats.csv");
        assertThat(html).as("CSP 'self': no inline styles or scripts").doesNotContain("style=")
                .doesNotContainPattern("<script(?![^>]*\\bsrc=)");
    }

    @Test
    void courseCardAndHistoryLinkToTheStats() throws Exception {
        Fixture f = openFixture();
        String link = "/teacher/courses/" + f.courseId() + "/stats";

        mvc.perform(get("/teacher/courses").with(owner(f)))
                .andExpect(content().string(containsString("href=\"" + link + "\"")));
        mvc.perform(get("/teacher/courses/{id}", f.courseId()).with(owner(f)))
                .andExpect(content().string(containsString("href=\"" + link + "\"")));
    }

    @Test
    void ownerDownloadsTheStatsAsCsv() throws Exception {
        Course4 c = fourSessions();

        MvcResult result = mvc.perform(get("/teacher/courses/{id}/stats.csv", c.f().courseId()).with(owner(c.f())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();

        ContentDisposition disposition = disposition(result);
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("CS101_stats_2026-11-06.csv");
        assertThat(result.getResponse().getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo("﻿"
                + "Nr.;Last name;First name;Email;Attended;Sessions;Percentage;Last attended;Source\r\n"
                + "1;Dobre;Carmen;;2;4;50%;2026-11-06;manual\r\n"
                + "2;Popescu;Ana;sub-ana@student.example.ro;3;4;75%;2026-11-06;account\r\n"
                + "3;Șerban;Ion;sub-ion@student.example.ro;2;4;50%;2026-10-23;account\r\n"
                + "4;Țărănescu;Ștefan;sub-stefan@student.example.ro;1;4;25%;2026-10-16;account\r\n");
    }

    @Test
    void ownerDownloadsTheStatsAsXlsx() throws Exception {
        Course4 c = fourSessions();

        MvcResult result = mvc.perform(get("/teacher/courses/{id}/stats.xlsx", c.f().courseId()).with(owner(c.f())))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType(XLSX)))
                .andReturn();

        assertThat(disposition(result).getFilename()).isEqualTo("CS101_stats_2026-11-06.xlsx");
        byte[] xlsx = result.getResponse().getContentAsByteArray();
        try (ReadableWorkbook workbook = XlsxExporterTest.open(xlsx)) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo("Stats");
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(XlsxExporterTest.rowAt(rows, 0).getCellText(0)).isEqualTo("Algoritmi (CS101)");
            assertThat(XlsxExporterTest.rowAt(rows, 1).getCellText(0)).isEqualTo("Attendance per student on "
                    + "2026-11-06 · 4 sessions (1 without check-ins), 2026-10-16–2026-11-06 · 4 students (1 manual)");
            assertThat(XlsxExporterTest.rowAt(rows, 3).stream().map(Cell::getText).toList()).containsExactly(
                    "Nr.", "Last name", "First name", "Email", "Attended", "Sessions", "Percentage", "Last attended",
                    "Source");
            assertThat(XlsxExporterTest.rowAt(rows, 4).getCellText(1)).isEqualTo("Dobre");
            assertThat(XlsxExporterTest.rowAt(rows, 4).getCellText(8)).isEqualTo("manual");
            Row ana = XlsxExporterTest.rowAt(rows, 5);
            assertThat(ana.getCellText(1)).isEqualTo("Popescu");
            assertThat(ana.getCellAsNumber(4).orElseThrow().intValue()).isEqualTo(3);
            assertThat(ana.getCellAsNumber(5).orElseThrow().intValue()).isEqualTo(4);
            assertThat(ana.getCell(6).getType()).isEqualTo(CellType.NUMBER);
            assertThat(ana.getCell(6).asNumber().doubleValue()).isEqualTo(0.75);
            assertThat(ana.getCell(7).asDate()).isEqualTo(LocalDateTime.parse("2026-11-06T00:00"));
            assertThat(XlsxExporterTest.rowAt(rows, 6).getCellText(1)).isEqualTo("Șerban");
            assertThat(XlsxExporterTest.rowAt(rows, 7).getCellText(1)).isEqualTo("Țărănescu");
            assertThat(XlsxExporterTest.rowAt(rows, 8)).isNull();
        }
        String xml = XlsxExporterTest.sheetXml(xlsx);
        assertThat(xml).containsPattern("<pane[^>]*ySplit=\"4\"[^>]*state=\"frozen\"");
        assertThat(xml).contains("<autoFilter ref=\"A4:I8\"", "paperSize=\"9\"");
    }

    @Test
    void filenameFallsBackToTheCourseNameSlug() throws Exception {
        Teacher teacher = createTeacher("prof.slug@university.example.ro");
        Course course = courseService.createCourse(teacher.getId(), "Știința datelor", null);

        MvcResult result = mvc.perform(get("/teacher/courses/{id}/stats.xlsx", course.getId())
                        .with(teacherLogin(teacher.getEmail())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(disposition(result).getFilename()).isEqualTo("Stiinta_datelor_stats_2026-10-16.xlsx");
    }

    // ------------------------------------------------------------------ access

    @Test
    void anotherTeacherGets404OnThePageAndBothExports() throws Exception {
        Course4 c = fourSessions();
        Teacher otherTeacher = createTeacher("someone.else@university.example.ro");
        RequestPostProcessor other = teacherLogin(otherTeacher.getEmail());

        mvc.perform(get("/teacher/courses/{id}/stats", c.f().courseId()).with(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/teacher/courses/{id}/stats.xlsx", c.f().courseId()).with(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/teacher/courses/{id}/stats.csv", c.f().courseId()).with(other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/teacher/courses/{id}/stats.csv", 999_999L).with(owner(c.f())))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> statsService.courseStats(otherTeacher.getId(), c.f().courseId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void studentsAreForbiddenAndAnonymousUsersMustSignIn() throws Exception {
        Fixture f = openFixture();
        RequestPostProcessor student = studentLogin("sub-ana", "Ana", "Popescu");

        mvc.perform(get("/teacher/courses/{id}/stats", f.courseId()).with(student))
                .andExpect(status().isForbidden());
        mvc.perform(get("/teacher/courses/{id}/stats.xlsx", f.courseId()).with(student))
                .andExpect(status().isForbidden());
        mvc.perform(get("/teacher/courses/{id}/stats.csv", f.courseId()).with(student))
                .andExpect(status().isForbidden());
        mvc.perform(get("/teacher/courses/{id}/stats", f.courseId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
    }

    @Test
    void logsIdsAndCountsOnly(CapturedOutput output) throws Exception {
        Course4 c = fourSessions();

        mvc.perform(get("/teacher/courses/{id}/stats.csv", c.f().courseId()).with(owner(c.f())))
                .andExpect(status().isOk());

        assertThat(output).contains("Teacher " + c.f().teacherId() + " read the attendance stats of course "
                + c.f().courseId() + ": 4 session(s), 4 attendee(s)");
        assertThat(output).doesNotContain("Popescu", "Dobre", "Țărănescu", "sub-ana@student.example.ro");
    }

    // ------------------------------------------------------------------ helpers

    private UUID openSession(Fixture f) {
        return sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
    }

    private RequestPostProcessor owner(Fixture f) {
        Teacher teacher = teacherRepository.findById(f.teacherId()).orElseThrow();
        return teacherLogin(teacher.getEmail());
    }

    private static ContentDisposition disposition(MvcResult result) {
        return ContentDisposition.parse(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
    }
}
