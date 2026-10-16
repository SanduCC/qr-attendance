package io.github.sanducc.attendance.export;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.checkin.RecordView;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CsvExporterTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final String BOM = "﻿";
    private static final String HEADER = "Nr.;Last name;First name;Email;Check-in time;Source";
    private static final Instant OPENED = Instant.parse("2026-10-16T06:00:00Z");

    @Test
    void startsWithBomAndHeaderEvenWhenEmpty() throws Exception {
        String csv = export(List.of());

        assertThat(csv).isEqualTo(BOM + HEADER + "\r\n");
    }

    @Test
    void writesSemicolonSeparatedCrLfRowsSortedAndNumbered() throws Exception {
        String csv = export(List.of(
                scanned(1L, "Ștefan", "Țărănescu", "stefan@student.example.ro",
                        Instant.parse("2026-10-16T06:02:03Z")),
                scanned(2L, "Ioana-Maria", "Popescu", "ioana@student.example.ro",
                        Instant.parse("2026-10-16T06:03:00Z"))));

        assertThat(csv).isEqualTo(BOM + HEADER + "\r\n"
                + "1;Popescu;Ioana-Maria;ioana@student.example.ro;2026-10-16 09:03:00;scan\r\n"
                + "2;Țărănescu;Ștefan;stefan@student.example.ro;2026-10-16 09:02:03;scan\r\n");
    }

    @Test
    void manualEntriesAreMarkedInTheSourceColumn() throws Exception {
        String csv = export(List.of(
                scanned(1L, "Ana", "Popescu", "ana@student.example.ro", Instant.parse("2026-10-16T06:01:00Z")),
                new RecordView(2L, 7L, "Bogdan", "Ionescu", "bogdan@student.example.ro",
                        Instant.parse("2026-10-16T06:20:00Z"), true),
                new RecordView(3L, null, "Carmen", "Dobre", null, Instant.parse("2026-10-16T06:21:00Z"), true)));

        assertThat(csv).isEqualTo(BOM + HEADER + "\r\n"
                + "1;Dobre;Carmen;;2026-10-16 09:21:00;manual\r\n"
                + "2;Ionescu;Bogdan;bogdan@student.example.ro;2026-10-16 09:20:00;manual\r\n"
                + "3;Popescu;Ana;ana@student.example.ro;2026-10-16 09:01:00;scan\r\n");
    }

    @Test
    void sortsByRomanianAlphabetThenFirstNameThenCheckInTime() throws Exception {
        String csv = export(List.of(
                record("Ana", "Țepeș", "06:00:01"),
                record("Ion", "Tudor", "06:00:02"),
                record("Ana", "Ștefan", "06:00:03"),
                record("Ana", "Șerban", "06:00:04"),
                record("Ana", "Stan", "06:00:05"),
                record("Mihai", "Stan", "06:00:06"),
                record("Bogdan", "Stan", "06:00:07"),
                record("Bogdan", "Stan", "06:00:00")));

        assertThat(csv.lines().skip(1).toList()).containsExactly(
                "1;Stan;Ana;;2026-10-16 09:00:05;scan",
                "2;Stan;Bogdan;;2026-10-16 09:00:00;scan",
                "3;Stan;Bogdan;;2026-10-16 09:00:07;scan",
                "4;Stan;Mihai;;2026-10-16 09:00:06;scan",
                "5;Șerban;Ana;;2026-10-16 09:00:04;scan",
                "6;Ștefan;Ana;;2026-10-16 09:00:03;scan",
                "7;Tudor;Ion;;2026-10-16 09:00:02;scan",
                "8;Țepeș;Ana;;2026-10-16 09:00:01;scan");
    }

    @Test
    void rendersTimestampsInBucharestAcrossDaylightSavingTime() throws Exception {
        // Summer (EEST, UTC+3) and winter (EET, UTC+2).
        String csv = export(List.of(
                scanned(1L, "Ana", "Pop", null, Instant.parse("2026-07-01T21:30:00Z")),
                scanned(2L, "Ana", "Pop", null, Instant.parse("2026-01-15T07:00:00Z"))));

        assertThat(csv).contains("1;Pop;Ana;;2026-01-15 09:00:00;scan\r\n", "2;Pop;Ana;;2026-07-02 00:30:00;scan\r\n");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "Popescu                | Popescu",
            "Pop; Ana               | `\"Pop; Ana\"`",
            "Pop, Ana               | `Pop, Ana`",
            "O\"Neil                | `\"O\"\"Neil\"`",
            "O'Neil                 | O'Neil",
    })
    void quotesCellsOnlyWhenNeeded(String value, String expected) {
        assertThat(CsvExporter.cell(value)).isEqualTo(expected);
    }

    @Test
    void quotesEmbeddedLineBreaks() {
        assertThat(CsvExporter.cell("Ana\nPop")).isEqualTo("\"Ana\nPop\"");
        assertThat(CsvExporter.cell("Ana\r\nPop")).isEqualTo("\"Ana\r\nPop\"");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "=HYPERLINK(\"http://evil\") | `\"'=HYPERLINK(\"\"http://evil\"\")\"`",
            "+40712345678                | `\"'+40712345678\"`",
            "-2+3                        | `\"'-2+3\"`",
            "@SUM(A1:A2)                 | `\"'@SUM(A1:A2)\"`",
    })
    void neutralisesSpreadsheetFormulas(String value, String expected) {
        assertThat(CsvExporter.cell(value)).isEqualTo(expected);
    }

    @Test
    void neutralisesTabAndCarriageReturnPrefixes() {
        assertThat(CsvExporter.cell("\t=1")).isEqualTo("\"'\t=1\"");
        assertThat(CsvExporter.cell("\r=1")).isEqualTo("\"'\r=1\"");
    }

    @Test
    void formulaCharactersInsideAValueAreLeftAlone() {
        assertThat(CsvExporter.cell("Ioana-Maria")).isEqualTo("Ioana-Maria");
        assertThat(CsvExporter.cell("ana@example.ro")).isEqualTo("ana@example.ro");
    }

    @Test
    void emptyAndNullCells() {
        assertThat(CsvExporter.cell("")).isEmpty();
        assertThat(CsvExporter.cell(null)).isEmpty();
    }

    @Test
    void neutralisesFormulasInWholeRows() throws Exception {
        String csv = export(List.of(scanned(1L, "=cmd|' /C calc'!A0", "@evil", "+x@example.ro",
                Instant.parse("2026-10-16T06:00:00Z"))));

        assertThat(csv).endsWith("1;\"'@evil\";\"'=cmd|' /C calc'!A0\";\"'+x@example.ro\";2026-10-16 09:00:00;scan\r\n");
    }

    private static RecordView record(String firstName, String lastName, String utcTime) {
        return scanned(null, firstName, lastName, null, Instant.parse("2026-10-16T" + utcTime + "Z"));
    }

    private static String export(List<RecordView> records) throws Exception {
        AttendanceSheet sheet = AttendanceSheet.of("Algoritmi", "CS101", OPENED, OPENED.plusSeconds(900), null,
                records, BUCHAREST);
        StringWriter out = new StringWriter();
        CsvExporter.write(out, sheet);
        return out.toString();
    }

    /** A scanned check-in (not manual, no student id needed here). */
    static RecordView scanned(Long id, String firstName, String lastName, String email, Instant checkedInAt) {
        return new RecordView(id, null, firstName, lastName, email, checkedInAt, false);
    }
}
