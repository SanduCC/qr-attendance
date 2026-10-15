package io.github.sanducc.attendance.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.sanducc.attendance.checkin.RecordView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AttendanceSheetTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final Instant OPENED = Instant.parse("2026-10-16T06:00:00Z");
    private static final Instant CLOSES = Instant.parse("2026-10-16T06:15:00Z");

    @Test
    void titleHasTheCodeInParenthesesWhenThereIsOne() {
        assertThat(sheet("Algoritmi", "CS101", null, List.of()).title()).isEqualTo("Algoritmi (CS101)");
        assertThat(sheet("Algoritmi", " ", null, List.of()).title()).isEqualTo("Algoritmi");
        assertThat(sheet("Algoritmi", null, null, List.of()).title()).isEqualTo("Algoritmi");
    }

    @Test
    void subtitleShowsTheWindowInBucharestAndTheHeadcount() {
        List<RecordView> two = List.of(record("Ana", "Pop", "06:01:00"), record("Ion", "Pop", "06:02:00"));

        assertThat(sheet("Algoritmi", "CS101", null, two).subtitle())
                .isEqualTo("2026-10-16 09:00–09:15 · 2 checked in");
        assertThat(sheet("Algoritmi", "CS101", 25, two).subtitle())
                .isEqualTo("2026-10-16 09:00–09:15 · 2 checked in / expected 25");
    }

    @Test
    void windowAcrossMidnightRepeatsTheDate() {
        AttendanceSheet sheet = AttendanceSheet.of("Algoritmi", "CS101", Instant.parse("2026-10-16T20:50:00Z"),
                Instant.parse("2026-10-16T21:05:00Z"), null, List.of(), BUCHAREST);

        assertThat(sheet.subtitle()).startsWith("2026-10-16 23:50–2026-10-17 00:05 · ");
        assertThat(sheet.filename("csv")).isEqualTo("CS101_2026-10-16.csv");
    }

    @Test
    void sortsByRomanianAlphabetAndNumbersAfterSorting() {
        AttendanceSheet sheet = sheet("Algoritmi", "CS101", null, List.of(
                record("Ana", "Țepeș", "06:00:01"),
                record("Ana", "Tudor", "06:00:02"),
                record("Ana", "Ștefan", "06:00:03"),
                record("Ana", "Șerban", "06:00:04"),
                record("Ana", "Stan", "06:00:05"),
                record("Ana", "Sârbu", "06:00:06"),
                record("Ana", "Îonescu", "06:00:07"),
                record("Ana", "Iordache", "06:00:08"),
                record("Ana", "Âtu", "06:00:09"),
                record("Ana", "Ăbăcioaie", "06:00:10"),
                record("Ana", "Abra", "06:00:11")));

        assertThat(sheet.rows()).extracting(AttendanceSheet.Row::lastName).containsExactly(
                "Abra", "Ăbăcioaie", "Âtu", "Iordache", "Îonescu", "Sârbu", "Stan", "Șerban", "Ștefan", "Tudor",
                "Țepeș");
        assertThat(sheet.rows()).extracting(AttendanceSheet.Row::nr)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    @Test
    void legacyCedillaAndDecomposedLettersSortWithTheirCommaBelowLetter() {
        AttendanceSheet sheet = sheet("Algoritmi", "CS101", null, List.of(
                record("Ana", "Tudor", "06:00:01"),
                record("Ana", "Şerban", "06:00:02"),          // Ş with cedilla (old keyboards)
                record("Ana", "Ștefan", "06:00:03"),         // S + combining comma below (NFD)
                record("Ana", "Stoica", "06:00:04")));

        assertThat(sheet.rows()).extracting(AttendanceSheet.Row::lastName)
                .containsExactly("Stoica", "Şerban", "Ștefan", "Tudor");
    }

    @Test
    void tiesAreBrokenByFirstNameThenCheckInTime() {
        AttendanceSheet sheet = sheet("Algoritmi", "CS101", null, List.of(
                record("Ștefan", "Pop", "06:00:01"),
                record("Sorin", "Pop", "06:00:09"),
                record("Sorin", "Pop", "06:00:03")));

        assertThat(sheet.rows()).extracting(AttendanceSheet.Row::firstName, AttendanceSheet.Row::checkedInAt)
                .containsExactly(
                        tuple("Sorin", LocalDateTime.parse("2026-10-16T09:00:03")),
                        tuple("Sorin", LocalDateTime.parse("2026-10-16T09:00:09")),
                        tuple("Ștefan", LocalDateTime.parse("2026-10-16T09:00:01")));
    }

    @Test
    void checkInTimesAreConvertedToBucharest() {
        AttendanceSheet sheet = sheet("Algoritmi", "CS101", null, List.of(
                new RecordView(1L, "Ana", "Pop", null, Instant.parse("2026-07-01T21:30:00Z"))));

        assertThat(sheet.rows().getFirst().checkedInAt()).isEqualTo(LocalDateTime.parse("2026-07-02T00:30:00"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "CS101          | Algoritmi                    | CS101",
            "SD-2026        | Structuri de date            | SD-2026",
            "' CS 101/A '   | Algoritmi                    | CS_101_A",
            "ȘD             | Algoritmi                    | SD",
            "NULL           | Structuri de date            | Structuri_de_date",
            "' '            | Știința datelor și Țesături  | Stiinta_datelor_si_Tesaturi",
            "NULL           | '  Ăla, bălan -- (2026)!  '  | Ala_balan_2026",
            "NULL           | 'Programare orientată pe obiecte și ingineria aplicațiilor web' | Programare_orientata_pe_obiecte_si_ingin",
            "NULL           | '!!!'                        | attendance",
            "NULL           | 数据结构                      | attendance",
            "NULL           | NULL                         | attendance",
    })
    void filenameBaseFallsBackFromCodeToNameSlugToAttendance(String code, String name, String expected) {
        assertThat(AttendanceSheet.filenameBase(code, name)).isEqualTo(expected);
    }

    @Test
    void filenameIsBaseAndSessionDateInBucharest() {
        // 23:30 UTC is already the next day in Bucharest.
        AttendanceSheet sheet = AttendanceSheet.of("Algoritmi", null, Instant.parse("2026-10-16T22:30:00Z"),
                Instant.parse("2026-10-16T22:45:00Z"), null, List.of(), BUCHAREST);

        assertThat(sheet.filename("xlsx")).isEqualTo("Algoritmi_2026-10-17.xlsx");
    }

    private static AttendanceSheet sheet(String name, String code, Integer expected, List<RecordView> records) {
        return AttendanceSheet.of(name, code, OPENED, CLOSES, expected, records, BUCHAREST);
    }

    private static RecordView record(String firstName, String lastName, String utcTime) {
        return new RecordView(null, firstName, lastName, null, Instant.parse("2026-10-16T" + utcTime + "Z"));
    }
}
