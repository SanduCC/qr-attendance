package io.github.sanducc.attendance.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class ExportTest extends IntegrationTestSupport {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test
    void ownerDownloadsASortedXlsx() throws Exception {
        Fixture f = openFixture(25);
        insertRecord(f.sessionId(), insertStudent("sub-1", "Ștefan", "Țărănescu"));
        clock.advance(Duration.ofSeconds(7));
        insertRecord(f.sessionId(), insertStudent("sub-2", "Ana", "Popescu"));
        clock.advance(Duration.ofSeconds(7));
        insertRecord(f.sessionId(), insertStudent("sub-3", "Ion", "Șerban"));

        MvcResult result = mvc.perform(get("/sessions/{id}/export.xlsx", f.sessionId()).with(owner(f)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.parseMediaType(XLSX)))
                .andReturn();

        ContentDisposition disposition = disposition(result);
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("CS101_2026-10-16.xlsx");

        try (ReadableWorkbook workbook = XlsxExporterTest.open(result.getResponse().getContentAsByteArray())) {
            assertThat(workbook.getFirstSheet().getName()).isEqualTo("Attendance");
            List<Row> rows = workbook.getFirstSheet().read();
            assertThat(XlsxExporterTest.rowAt(rows, 0).getCellText(0)).isEqualTo("Algoritmi (CS101)");
            assertThat(XlsxExporterTest.rowAt(rows, 1).getCellText(0))
                    .isEqualTo("2026-10-16 09:00–09:15 · 3 checked in / expected 25");
            assertThat(XlsxExporterTest.rowAt(rows, 3).getCellText(1)).isEqualTo("Last name");

            Row first = XlsxExporterTest.rowAt(rows, 4);
            assertThat(first.getCellAsNumber(0).orElseThrow().intValue()).isEqualTo(1);
            assertThat(first.getCellText(1)).isEqualTo("Popescu");
            assertThat(first.getCellText(2)).isEqualTo("Ana");
            assertThat(first.getCellText(3)).isEqualTo("sub-2@student.example.ro");
            assertThat(first.getCell(4).getType()).isEqualTo(CellType.NUMBER);
            assertThat(first.getCell(4).asDate()).isEqualTo(LocalDateTime.parse("2026-10-16T09:00:12"));
            assertThat(XlsxExporterTest.rowAt(rows, 5).getCellText(1)).isEqualTo("Șerban");
            assertThat(XlsxExporterTest.rowAt(rows, 6).getCellText(1)).isEqualTo("Țărănescu");
            assertThat(XlsxExporterTest.rowAt(rows, 7)).isNull();
        }
    }

    @Test
    void ownerDownloadsASemicolonCsvInTheSameOrder() throws Exception {
        Fixture f = openFixture();
        insertRecord(f.sessionId(), insertStudent("sub-1", "Ștefan", "Țărănescu"));
        insertRecord(f.sessionId(), insertStudent("sub-2", "Ana", "Popescu"));

        MvcResult result = mvc.perform(get("/sessions/{id}/export.csv", f.sessionId()).with(owner(f)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();

        ContentDisposition disposition = disposition(result);
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("CS101_2026-10-16.csv");
        assertThat(result.getResponse().getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo("﻿Nr.;Last name;First name;Email;Check-in time\r\n"
                + "1;Popescu;Ana;sub-2@student.example.ro;2026-10-16 09:00:05\r\n"
                + "2;Țărănescu;Ștefan;sub-1@student.example.ro;2026-10-16 09:00:05\r\n");
    }

    @Test
    void anotherTeacherGets404() throws Exception {
        Fixture f = openFixture();
        insertRecord(f.sessionId(), insertStudent("sub-1", "Ana", "Popescu"));
        RequestPostProcessor other = teacherLogin(createTeacher("someone.else@university.example.ro").getEmail());

        mvc.perform(get("/sessions/{id}/export.xlsx", f.sessionId()).with(other)).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/{id}/export.csv", f.sessionId()).with(other)).andExpect(status().isNotFound());
    }

    @Test
    void studentsAreForbiddenAndAnonymousUsersMustSignIn() throws Exception {
        Fixture f = openFixture();
        RequestPostProcessor student = studentLogin("sub-ana", "Ana", "Popescu");

        mvc.perform(get("/sessions/{id}/export.xlsx", f.sessionId()).with(student))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/export.csv", f.sessionId()).with(student))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/export.xlsx", f.sessionId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
    }

    private RequestPostProcessor owner(Fixture f) {
        Teacher teacher = teacherRepository.findById(f.teacherId()).orElseThrow();
        return teacherLogin(teacher.getEmail());
    }

    private static ContentDisposition disposition(MvcResult result) {
        return ContentDisposition.parse(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
    }
}
