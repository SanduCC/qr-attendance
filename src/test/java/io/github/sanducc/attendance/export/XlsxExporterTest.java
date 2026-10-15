package io.github.sanducc.attendance.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.checkin.RecordView;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;

class XlsxExporterTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final Instant OPENED = Instant.parse("2026-10-16T06:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-10-16T06:15:00Z");

    @Test
    void writesTitleBlockAndHeaderOnTheAttendanceSheet() throws Exception {
        byte[] xlsx = export(25, List.of(record("Ana", "Pop", "ana@student.example.ro", "06:01:00")));

        try (ReadableWorkbook workbook = open(xlsx)) {
            Sheet sheet = workbook.getFirstSheet();
            assertThat(sheet.getName()).isEqualTo("Attendance");
            List<Row> rows = sheet.read();
            assertThat(text(rows.get(0), 0)).isEqualTo("Algoritmi (CS101)");
            assertThat(text(rows.get(1), 0)).isEqualTo("2026-10-16 09:00–09:15 · 1 checked in / expected 25");
            assertThat(rowAt(rows, 2)).as("blank spacer row").isNull();
            assertThat(rowAt(rows, 3).stream().map(Cell::getText).toList())
                    .containsExactly("Nr.", "Last name", "First name", "Email", "Check-in time");
        }
    }

    @Test
    void writesSortedNumberedRowsWithARealTimeCell() throws Exception {
        byte[] xlsx = export(null, List.of(
                record("Ana", "Țepeș", "ana@student.example.ro", "06:00:01"),
                record("Ion", "Tudor", null, "06:00:02"),
                record("Ana", "Ștefan", "stefan@student.example.ro", "06:00:03"),
                record("Ana", "Șerban", "serban@student.example.ro", "06:00:04"),
                record("Ana", "Stan", "stan@student.example.ro", "06:02:05")));

        try (ReadableWorkbook workbook = open(xlsx)) {
            List<Row> rows = workbook.getFirstSheet().read();
            List<Row> data = rows.subList(rows.indexOf(rowAt(rows, 3)) + 1, rows.size());

            assertThat(data).extracting(r -> r.getCellText(1))
                    .containsExactly("Stan", "Șerban", "Ștefan", "Tudor", "Țepeș");
            assertThat(data).extracting(r -> r.getCell(0).getType()).containsOnly(CellType.NUMBER);
            assertThat(data).extracting(r -> r.getCellAsNumber(0).orElseThrow().intValue())
                    .containsExactly(1, 2, 3, 4, 5);

            Row stan = data.getFirst();
            assertThat(stan.getCellText(2)).isEqualTo("Ana");
            assertThat(stan.getCell(3).getType()).isEqualTo(CellType.STRING);
            assertThat(stan.getCellText(3)).isEqualTo("stan@student.example.ro");
            Cell time = stan.getCell(4);
            assertThat(time.getType()).as("check-in time is a date/time value, not text").isEqualTo(CellType.NUMBER);
            assertThat(time.asDate()).isEqualTo(LocalDateTime.parse("2026-10-16T09:02:05"));
            assertThat(time.getDataFormatString()).isEqualTo("HH:mm:ss");

            Row tudor = data.get(3);
            assertThat(tudor.getOptionalCell(3).map(Cell::getType).orElse(CellType.EMPTY))
                    .as("missing email is an empty cell").isEqualTo(CellType.EMPTY);
        }
    }

    @Test
    void formulaLikeNamesStayPlainStrings() throws Exception {
        byte[] xlsx = export(null, List.of(new RecordView(1L, "=cmd|' /C calc'!A0", "@SUM(A1:A2)",
                "+x@example.ro", OPENED)));

        try (ReadableWorkbook workbook = open(xlsx)) {
            List<Row> rows = workbook.getFirstSheet().read();
            Row row = rows.getLast();
            for (int col = 1; col <= 3; col++) {
                assertThat(row.getCell(col).getType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(col).getFormula()).isNull();
            }
            assertThat(row.getCellText(1)).isEqualTo("@SUM(A1:A2)");
            assertThat(row.getCellText(2)).isEqualTo("=cmd|' /C calc'!A0");
            assertThat(row.getCellText(3)).isEqualTo("+x@example.ro");
        }
        assertThat(sheetXml(xlsx)).doesNotContain("<f>");
    }

    @Test
    void headerIsFrozenAndFilterable() throws Exception {
        byte[] xlsx = export(null, List.of(
                record("Ana", "Pop", null, "06:00:01"),
                record("Ion", "Pop", null, "06:00:02")));

        String xml = sheetXml(xlsx);
        assertThat(xml).containsPattern("<pane[^>]*ySplit=\"4\"[^>]*state=\"frozen\"");
        assertThat(xml).contains("<autoFilter ref=\"A4:E6\"");
        assertThat(xml).contains("<col ");
        assertThat(xml).as("A4 paper for printing").contains("paperSize=\"9\"");
    }

    @Test
    void emptySessionStillHasTitleAndHeader() throws Exception {
        byte[] xlsx = export(null, List.of());

        try (ReadableWorkbook workbook = open(xlsx)) {
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(text(rows.getFirst(), 0)).isEqualTo("Algoritmi (CS101)");
            assertThat(rowAt(rows, 3).getCellText(0)).isEqualTo("Nr.");
            assertThat(rows.getLast()).isSameAs(rowAt(rows, 3));
        }
    }

    @Test
    void columnWidthsFollowTheContentWithinLimits() {
        assertThat(XlsxExporter.width("Nr.", List.of("1", "12"))).isEqualTo(6);
        assertThat(XlsxExporter.width("Email", List.of("ioana-maria.popescu@student.example.ro")))
                .isEqualTo("ioana-maria.popescu@student.example.ro".length() + 3);
        assertThat(XlsxExporter.width("Email", Arrays.asList(null, "x".repeat(200)))).isEqualTo(50);
    }

    // ------------------------------------------------------------------ helpers (also used by ExportTest)

    static ReadableWorkbook open(byte[] xlsx) throws IOException {
        return new ReadableWorkbook(new ByteArrayInputStream(xlsx), new ReadingOptions(true, false));
    }

    /** The row with this 0-based index, or null if the sheet has no such row (blank rows aren't written). */
    static Row rowAt(List<Row> rows, int index) {
        return rows.stream().filter(r -> r.getRowNum() == index + 1).findFirst().orElse(null);
    }

    static String text(Row row, int col) {
        return row.getCellText(col);
    }

    /** The raw sheet XML, read through the zip's central directory (the entries use data descriptors). */
    static String sheetXml(byte[] xlsx) throws IOException {
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(xlsx)).get()) {
            ZipArchiveEntry entry = zip.getEntry("xl/worksheets/sheet1.xml");
            assertThat(entry).as("xl/worksheets/sheet1.xml in the workbook").isNotNull();
            try (InputStream in = zip.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static byte[] export(Integer expected, List<RecordView> records) throws IOException {
        AttendanceSheet sheet = AttendanceSheet.of("Algoritmi", "CS101", OPENED, CLOSES, expected, records, BUCHAREST);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxExporter.write(out, sheet);
        return out.toByteArray();
    }

    private static RecordView record(String firstName, String lastName, String email, String utcTime) {
        return new RecordView(null, firstName, lastName, email, Instant.parse("2026-10-16T" + utcTime + "Z"));
    }
}
