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
    private static final String HEADER = "last_name,first_name,email,checked_in_at";

    @Test
    void startsWithBomAndHeaderEvenWhenEmpty() throws Exception {
        String csv = export(List.of());

        assertThat(csv).isEqualTo(BOM + HEADER + "\r\n");
    }

    @Test
    void writesOneCrLfTerminatedRowPerRecordInLastFirstOrder() throws Exception {
        String csv = export(List.of(
                new RecordView(1L, "Ștefan", "Țărănescu", "stefan@student.example.ro",
                        Instant.parse("2026-10-16T06:02:03Z")),
                new RecordView(2L, "Ioana-Maria", "Popescu", "ioana@student.example.ro",
                        Instant.parse("2026-10-16T06:03:00Z"))));

        assertThat(csv).isEqualTo(BOM + HEADER + "\r\n"
                + "Țărănescu,Ștefan,stefan@student.example.ro,2026-10-16 09:02:03\r\n"
                + "Popescu,Ioana-Maria,ioana@student.example.ro,2026-10-16 09:03:00\r\n");
    }

    @Test
    void rendersTimestampsInBucharestAcrossDaylightSavingTime() throws Exception {
        // Summer (EEST, UTC+3) and winter (EET, UTC+2).
        String csv = export(List.of(
                new RecordView(1L, "Ana", "Pop", null, Instant.parse("2026-07-01T21:30:00Z")),
                new RecordView(2L, "Ana", "Pop", null, Instant.parse("2026-01-15T07:00:00Z"))));

        assertThat(csv).contains("Pop,Ana,,2026-07-02 00:30:00\r\n", "Pop,Ana,,2026-01-15 09:00:00\r\n");
    }

    @Test
    void nullEmailIsAnEmptyCell() throws Exception {
        String csv = export(List.of(new RecordView(1L, "Ana", "Pop", null, Instant.parse("2026-10-16T06:00:00Z"))));

        assertThat(csv).endsWith("Pop,Ana,,2026-10-16 09:00:00\r\n");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
            "Popescu                | Popescu",
            "Pop, Ana               | `\"Pop, Ana\"`",
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
        String csv = export(List.of(new RecordView(1L, "=cmd|' /C calc'!A0", "@evil", "+x@example.ro",
                Instant.parse("2026-10-16T06:00:00Z"))));

        assertThat(csv).endsWith("\"'@evil\",\"'=cmd|' /C calc'!A0\",\"'+x@example.ro\",2026-10-16 09:00:00\r\n");
    }

    private static String export(List<RecordView> records) throws Exception {
        StringWriter out = new StringWriter();
        CsvExporter.write(out, records, BUCHAREST);
        return out.toString();
    }
}
