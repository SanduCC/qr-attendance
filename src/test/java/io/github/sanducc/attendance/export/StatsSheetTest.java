package io.github.sanducc.attendance.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.stats.AttendeeStats;
import io.github.sanducc.attendance.stats.CourseStats;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;

/** The per-student stats export (#8): the sheet model, the CSV and the XLSX, with the session exports' conventions. */
class StatsSheetTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final LocalDate TODAY = LocalDate.parse("2026-12-11");
    private static final Instant FIRST = Instant.parse("2026-10-02T06:00:00Z");
    private static final Instant LAST = Instant.parse("2026-12-11T07:00:00Z");
    private static final String BOM = "﻿";
    private static final String HEADER = "Nr.;Last name;First name;Email;Attended;Sessions;Percentage;Last attended;"
            + "Source";

    @Test
    void titleBlockAndFilenameFollowTheSessionExports() {
        StatsSheet sheet = StatsSheet.of(stats("Algoritmi", "CS101", 10, 1, List.of(
                student(1L, "Ana", "Popescu", 8, LAST),
                typed("Carmen", "Dobre", 2, FIRST))), BUCHAREST, TODAY);

        assertThat(sheet.title()).isEqualTo("Algoritmi (CS101)");
        assertThat(sheet.subtitle()).isEqualTo("Attendance per student on 2026-12-11 · 10 sessions (1 without "
                + "check-ins), 2026-10-02–2026-12-11 · 2 students (1 manual)");
        assertThat(sheet.filename("csv")).isEqualTo("CS101_stats_2026-12-11.csv");
        assertThat(StatsSheet.of(stats("Știința datelor", null, 1, 0, List.of()), BUCHAREST, TODAY)
                .filename("xlsx")).isEqualTo("Stiinta_datelor_stats_2026-12-11.xlsx");
    }

    @Test
    void subtitleWithOneSessionAndNoAttendees() {
        StatsSheet sheet = StatsSheet.of(new CourseStats(course("Algoritmi", null), 1, 1, FIRST, FIRST, List.of()),
                BUCHAREST, TODAY);

        assertThat(sheet.subtitle())
                .isEqualTo("Attendance per student on 2026-12-11 · 1 session (1 without check-ins), 2026-10-02 · "
                        + "0 students");
        assertThat(StatsSheet.of(new CourseStats(course("Algoritmi", null), 0, 0, null, null, List.of()),
                BUCHAREST, TODAY).subtitle()).isEqualTo("Attendance per student on 2026-12-11 · 0 sessions · "
                + "0 students");
    }

    @Test
    void rowsKeepTheGivenOrderAndRoundPercentagesDown() {
        StatsSheet sheet = StatsSheet.of(stats("Algoritmi", "CS101", 3, 0, List.of(
                student(1L, "Ana", "Popescu", 3, LAST),
                student(2L, "Ion", "Șerban", 2, FIRST),
                typed("Carmen", "Ștefan", 1, FIRST))), BUCHAREST, TODAY);

        assertThat(sheet.rows()).extracting(StatsSheet.Row::nr, StatsSheet.Row::lastName, StatsSheet.Row::attended,
                        StatsSheet.Row::sessions, StatsSheet.Row::percent, StatsSheet.Row::source)
                .containsExactly(
                        tuple(1, "Popescu", 3L, 3L, 100, "account"),
                        tuple(2, "Șerban", 2L, 3L, 66, "account"),
                        tuple(3, "Ștefan", 1L, 3L, 33, "manual"));
        assertThat(CourseStats.percent(199, 200)).as("never 100% before every session").isEqualTo(99);
        assertThat(CourseStats.percent(0, 0)).isZero();
    }

    @Test
    void lastAttendedIsTheSessionDateInBucharest() {
        // 22:30 UTC is already the next day in Bucharest.
        StatsSheet sheet = StatsSheet.of(stats("Algoritmi", "CS101", 1, 0, List.of(
                student(1L, "Ana", "Pop", 1, Instant.parse("2026-10-16T22:30:00Z")))), BUCHAREST, TODAY);

        assertThat(sheet.rows().getFirst().lastAttended()).isEqualTo(LocalDate.parse("2026-10-17"));
    }

    @Test
    void csvIsSemicolonSeparatedWithBomAndCrLf() throws Exception {
        String csv = csv(stats("Algoritmi", "CS101", 4, 1, List.of(
                typed("Carmen", "Dobre", 2, LAST),
                student(1L, "Ana", "Popescu", 3, LAST),
                student(2L, "Ștefan", "Țărănescu", 1, FIRST))));

        assertThat(csv).isEqualTo(BOM + HEADER + "\r\n"
                + "1;Dobre;Carmen;;2;4;50%;2026-12-11;manual\r\n"
                + "2;Popescu;Ana;ana@student.example.ro;3;4;75%;2026-12-11;account\r\n"
                + "3;Țărănescu;Ștefan;stefan@student.example.ro;1;4;25%;2026-10-02;account\r\n");
    }

    @Test
    void csvOfACourseWithoutAttendeesIsJustTheHeader() throws Exception {
        assertThat(csv(stats("Algoritmi", "CS101", 0, 0, List.of()))).isEqualTo(BOM + HEADER + "\r\n");
    }

    @Test
    void csvNeutralisesFormulaLikeNames() throws Exception {
        String csv = csv(stats("Algoritmi", "CS101", 1, 0, List.of(
                new AttendeeStats(1L, "=cmd|' /C calc'!A0", "@evil", "+x@example.ro", 1L, LAST))));

        assertThat(csv).endsWith("1;\"'@evil\";\"'=cmd|' /C calc'!A0\";\"'+x@example.ro\";1;1;100%;2026-12-11;"
                + "account\r\n");
    }

    @Test
    void xlsxHasTheTitleBlockAndHeaderOnTheStatsSheet() throws Exception {
        byte[] xlsx = xlsx(stats("Algoritmi", "CS101", 4, 1, List.of(
                student(1L, "Ana", "Popescu", 3, LAST),
                typed("Carmen", "Ștefan", 2, FIRST))));

        try (ReadableWorkbook workbook = XlsxExporterTest.open(xlsx)) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo("Stats");
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(XlsxExporterTest.rowAt(rows, 0).getCellText(0)).isEqualTo("Algoritmi (CS101)");
            assertThat(XlsxExporterTest.rowAt(rows, 1).getCellText(0)).startsWith("Attendance per student on ");
            assertThat(XlsxExporterTest.rowAt(rows, 2)).as("blank spacer row").isNull();
            assertThat(XlsxExporterTest.rowAt(rows, 3).stream().map(Cell::getText).toList()).containsExactly(
                    "Nr.", "Last name", "First name", "Email", "Attended", "Sessions", "Percentage", "Last attended",
                    "Source");

            Row ana = XlsxExporterTest.rowAt(rows, 4);
            assertThat(ana.getCellAsNumber(0).orElseThrow().intValue()).isEqualTo(1);
            assertThat(ana.getCellText(1)).isEqualTo("Popescu");
            assertThat(ana.getCellText(3)).isEqualTo("ana@student.example.ro");
            assertThat(ana.getCell(4).getType()).isEqualTo(CellType.NUMBER);
            assertThat(ana.getCellAsNumber(4).orElseThrow().intValue()).isEqualTo(3);
            assertThat(ana.getCellAsNumber(5).orElseThrow().intValue()).isEqualTo(4);
            Cell percent = ana.getCell(6);
            assertThat(percent.getType()).as("a real percentage, not text").isEqualTo(CellType.NUMBER);
            assertThat(percent.asNumber().doubleValue()).isEqualTo(0.75);
            assertThat(percent.getDataFormatString()).isEqualTo("0%");
            Cell last = ana.getCell(7);
            assertThat(last.getType()).as("a real date, not text").isEqualTo(CellType.NUMBER);
            assertThat(last.asDate()).isEqualTo(LocalDateTime.parse("2026-12-11T00:00"));
            assertThat(last.getDataFormatString()).isEqualTo("yyyy-mm-dd");
            assertThat(ana.getCellText(8)).isEqualTo("account");

            Row typed = XlsxExporterTest.rowAt(rows, 5);
            assertThat(typed.getOptionalCell(3).map(Cell::getType).orElse(CellType.EMPTY))
                    .as("a typed name has no email").isEqualTo(CellType.EMPTY);
            assertThat(typed.getCellText(8)).isEqualTo("manual");
        }
    }

    @Test
    void xlsxHeaderIsFrozenFilterableAndPrintsOnA4() throws Exception {
        byte[] xlsx = xlsx(stats("Algoritmi", "CS101", 2, 0, List.of(
                student(1L, "Ana", "Pop", 2, LAST),
                student(2L, "Ion", "Pop", 1, LAST))));

        String xml = XlsxExporterTest.sheetXml(xlsx);
        assertThat(xml).containsPattern("<pane[^>]*ySplit=\"4\"[^>]*state=\"frozen\"");
        assertThat(xml).contains("<autoFilter ref=\"A4:I6\"");
        assertThat(xml).contains("<col ");
        assertThat(xml).as("A4 paper for printing").contains("paperSize=\"9\"");
        assertThat(xml).contains("orientation=\"landscape\"");
        assertThat(xml).doesNotContain("<f>");
    }

    // ------------------------------------------------------------------ helpers

    private static String csv(CourseStats stats) throws Exception {
        StringWriter out = new StringWriter();
        CsvExporter.write(out, StatsSheet.of(stats, BUCHAREST, TODAY));
        return out.toString();
    }

    private static byte[] xlsx(CourseStats stats) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxExporter.write(out, StatsSheet.of(stats, BUCHAREST, TODAY));
        return out.toByteArray();
    }

    private static CourseStats stats(String name, String code, long total, long withoutEntries,
                                     List<AttendeeStats> attendees) {
        return new CourseStats(course(name, code), total, withoutEntries, total == 0 ? null : FIRST,
                total == 0 ? null : LAST, attendees);
    }

    private static Course course(String name, String code) {
        return new Course(1L, name, code, FIRST);
    }

    private static AttendeeStats student(Long id, String firstName, String lastName, long attended, Instant last) {
        String email = switch (firstName) {
            case "Ștefan" -> "stefan";
            default -> firstName.toLowerCase();
        } + "@student.example.ro";
        return new AttendeeStats(id, firstName, lastName, email, attended, last);
    }

    private static AttendeeStats typed(String firstName, String lastName, long attended, Instant last) {
        return new AttendeeStats(null, firstName, lastName, null, attended, last);
    }
}
