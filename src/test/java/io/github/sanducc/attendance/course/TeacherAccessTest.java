package io.github.sanducc.attendance.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.jayway.jsonpath.JsonPath;
import io.github.sanducc.attendance.checkin.AttendanceRecord;
import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.checkin.Student;
import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.SessionStatus;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class TeacherAccessTest extends IntegrationTestSupport {

    private static final String PROF_A = "prof.a@university.example.ro";
    private static final String PROF_B = "prof.b@university.example.ro";

    @Autowired AttendanceRecordService recordService;

    private RequestPostProcessor profA() {
        return teacherLogin(PROF_A);
    }

    // ------------------------------------------------------------------ roles

    @Test
    void unauthenticatedTeacherPagesRedirectToLogin() throws Exception {
        mvc.perform(get("/teacher/courses"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        mvc.perform(get("/sessions/{id}/display", UUID.randomUUID()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
    }

    @Test
    void studentsCannotReachTeacherPages() throws Exception {
        Fixture f = openFixture();
        RequestPostProcessor student = studentLogin("sub-ana", "Ana", "Popescu");

        mvc.perform(get("/teacher/courses").with(student)).andExpect(status().isForbidden());
        mvc.perform(post("/teacher/courses").with(csrf()).with(student).param("name", "X").param("code", "X"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/display", f.sessionId()).with(student)).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/qr-token", f.sessionId()).with(student)).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(student)).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/{id}/export.csv", f.sessionId()).with(student)).andExpect(status().isForbidden());

        assertThat(courseService.listCourses(f.teacherId())).hasSize(1);
    }

    @Test
    void teacherRoleWithoutATeacherRowIsForbidden() throws Exception {
        // E.g. a stale session after the teacher row was removed from the allowlist table.
        mvc.perform(get("/teacher/courses").with(teacherLogin("removed@university.example.ro")))
                .andExpect(status().isForbidden());
    }

    @Test
    void unverifiedTeacherEmailIsForbidden() throws Exception {
        createTeacher(PROF_A);
        RequestPostProcessor unverified = oidcLoginWithUnverifiedEmail(PROF_A);

        mvc.perform(get("/teacher/courses").with(unverified)).andExpect(status().isForbidden());
    }

    private RequestPostProcessor oidcLoginWithUnverifiedEmail(String email) {
        return oidcLogin()
                .clientRegistration(google())
                .idToken(t -> t.subject("sub-unverified").claim("email", email).claim("email_verified", false))
                .authorities(new SimpleGrantedAuthority("ROLE_TEACHER"));
    }

    // ------------------------------------------------------------------ course + session lifecycle

    @Test
    void teacherCreatesCourseOpensSessionAndGetsAValidRotatingQr() throws Exception {
        createTeacher(PROF_A);

        MvcResult created = mvc.perform(post("/teacher/courses").with(csrf()).with(profA())
                        .param("name", "  Structuri de date ").param("code", "SD-2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/teacher/courses/*"))
                .andReturn();
        long courseId = lastPathSegmentAsLong(created.getResponse().getRedirectedUrl());
        Course course = courseService.getOwnedCourse(teacherId(PROF_A), courseId);
        assertThat(course.getName()).isEqualTo("Structuri de date");
        assertThat(course.getCode()).isEqualTo("SD-2026");

        mvc.perform(get("/teacher/courses").with(profA()))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/courses"))
                .andExpect(model().attribute("courses", hasSize(1)));
        mvc.perform(get("/teacher/courses/{id}", courseId).with(profA()))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/course"))
                .andExpect(model().attribute("sessions", empty()));

        MvcResult opened = mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                        .param("durationMinutes", "20").param("expectedHeadcount", "25"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/sessions/*/display"))
                .andReturn();
        UUID sessionId = UUID.fromString(opened.getResponse().getRedirectedUrl().split("/")[2]);

        AttendanceSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getCourseId()).isEqualTo(courseId);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(session.getExpectedHeadcount()).isEqualTo(25);
        assertThat(session.getOpenedAt()).isEqualTo(clock.instant());
        assertThat(session.getClosesAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(20)));

        mvc.perform(get("/sessions/{id}/display", sessionId).with(profA()))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/display"))
                .andExpect(model().attribute("open", true))
                .andExpect(model().attribute("expectedHeadcount", 25))
                .andExpect(model().attribute("sessionId", sessionId));

        MvcResult qr = mvc.perform(get("/sessions/{id}/qr-token", sessionId).with(profA()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.url").value(startsWith("http://localhost/a/" + sessionId + "?t=")))
                .andExpect(jsonPath("$.svg").value(startsWith("<svg")))
                .andExpect(jsonPath("$.windowEndsAtMillis")
                        .value(qrTokens.current(sessionId).windowEndsAt().toEpochMilli()))
                .andExpect(jsonPath("$.serverTimeMillis").value(clock.millis()))
                .andReturn();
        String url = JsonPath.read(qr.getResponse().getContentAsString(), "$.url");
        String token = url.substring(url.indexOf("?t=") + 3);
        assertThat(qrTokens.isValid(sessionId, token)).isTrue();

        clock.advance(Duration.ofSeconds(30));
        assertThat(qrTokens.isValid(sessionId, token)).as("the projected token rotates").isFalse();
    }

    @Test
    void invalidCourseAndSessionFormsAreReRendered() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();
        long courseId = courseService.createCourse(teacherId, "Algoritmi", "CS101").getId();

        mvc.perform(post("/teacher/courses").with(csrf()).with(profA()).param("name", " ").param("code", "X"))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/courses"))
                .andExpect(model().attributeHasFieldErrors("courseForm", "name"));

        mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                        .param("durationMinutes", "0"))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/course"))
                .andExpect(model().attributeHasFieldErrors("openForm", "durationMinutes"));
        mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                        .param("durationMinutes", "181"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("openForm", "durationMinutes"));

        assertThat(sessionRepository.count()).isZero();
        assertThat(courseService.listCourses(teacherId)).hasSize(1);
    }

    /** Regression (#19): the code is required, so the form must not present it as optional. */
    @Test
    void courseCodeIsRequiredAndTheFormSaysSo() throws Exception {
        createTeacher(PROF_A);

        mvc.perform(post("/teacher/courses").with(csrf()).with(profA()).param("name", "Algoritmi").param("code", " "))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/courses"))
                .andExpect(model().attributeHasFieldErrors("courseForm", "code"));

        String html = mvc.perform(get("/teacher/courses").with(profA()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("(optional)");
        assertThat(html).containsPattern("<input[^>]*name=\"code\"[^>]*required");
    }

    @Test
    void openingASessionRequiresCsrf() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();
        long courseId = courseService.createCourse(teacherId, "Algoritmi", "CS101").getId();

        mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(profA()).param("durationMinutes", "15"))
                .andExpect(status().isForbidden());

        assertThat(sessionRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ live table (rule 5)

    @Test
    void recordsJsonFlagsMoreCheckInsThanExpected() throws Exception {
        Fixture f = fixtureForProfA(2);
        insertRecord(f.sessionId(), insertStudent("sub-1", "Ana", "Popescu"));
        clock.advance(Duration.ofSeconds(7));
        insertRecord(f.sessionId(), insertStudent("sub-2", "Bogdan", "Ionescu"));

        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.checkedIn").value(2))
                .andExpect(jsonPath("$.expectedHeadcount").value(2))
                .andExpect(jsonPath("$.overHeadcount").value(false))
                .andExpect(jsonPath("$.closesAt").value("09:15"))
                .andExpect(jsonPath("$.records", hasSize(2)))
                .andExpect(jsonPath("$.records[0].firstName").value("Ana"))
                .andExpect(jsonPath("$.records[0].lastName").value("Popescu"))
                .andExpect(jsonPath("$.records[0].email").value("sub-1@student.example.ro"))
                .andExpect(jsonPath("$.records[0].checkedInAt").value("09:00:05"))
                .andExpect(jsonPath("$.records[1].checkedInAt").value("09:00:12"));

        insertRecord(f.sessionId(), insertStudent("sub-3", "Carmen", "Dobre"));

        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(profA()))
                .andExpect(jsonPath("$.checkedIn").value(3))
                .andExpect(jsonPath("$.expectedHeadcount").value(2))
                .andExpect(jsonPath("$.overHeadcount").value(true));
    }

    @Test
    void withoutExpectedHeadcountThereIsNoWarning() throws Exception {
        Fixture f = fixtureForProfA(null);
        insertRecord(f.sessionId(), insertStudent("sub-1", "Ana", "Popescu"));

        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(profA()))
                .andExpect(jsonPath("$.checkedIn").value(1))
                .andExpect(jsonPath("$.expectedHeadcount").value(nullValue()))
                .andExpect(jsonPath("$.overHeadcount").value(false));
    }

    @Test
    void teacherRemovesASuspiciousEntry() throws Exception {
        Fixture f = fixtureForProfA(null);
        AttendanceRecord keep = insertRecord(f.sessionId(), insertStudent("sub-1", "Ana", "Popescu"));
        AttendanceRecord suspicious = insertRecord(f.sessionId(), insertStudent("sub-2", "Bogdan", "Ionescu"));

        mvc.perform(delete("/sessions/{id}/records/{rid}", f.sessionId(), suspicious.getId()).with(profA()))
                .andExpect(status().isForbidden());
        assertThat(recordRepository.existsById(suspicious.getId())).as("CSRF required").isTrue();

        mvc.perform(delete("/sessions/{id}/records/{rid}", f.sessionId(), suspicious.getId())
                        .with(csrf()).with(profA()))
                .andExpect(status().isNoContent());

        assertThat(recordRepository.existsById(suspicious.getId())).isFalse();
        assertThat(recordRepository.existsById(keep.getId())).isTrue();

        mvc.perform(delete("/sessions/{id}/records/{rid}", f.sessionId(), suspicious.getId())
                        .with(csrf()).with(profA()))
                .andExpect(status().isNotFound());
    }

    @Test
    void recordFromAnotherSessionCannotBeDeletedThroughThisOne() throws Exception {
        Fixture f = fixtureForProfA(null);
        UUID other = sessionService.openSession(f.teacherId(), f.courseId(), 15, null).getId();
        AttendanceRecord inOther = insertRecord(other, insertStudent("sub-1", "Ana", "Popescu"));

        mvc.perform(delete("/sessions/{id}/records/{rid}", f.sessionId(), inOther.getId())
                        .with(csrf()).with(profA()))
                .andExpect(status().isNotFound());

        assertThat(recordRepository.existsById(inOther.getId())).isTrue();
    }

    // ------------------------------------------------------------------ close + export

    @Test
    void closingTheSessionStopsTheQr() throws Exception {
        Fixture f = fixtureForProfA(null);
        clock.advance(Duration.ofMinutes(5));

        mvc.perform(post("/sessions/{id}/close", f.sessionId()).with(csrf()).with(profA()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/sessions/" + f.sessionId() + "/display"));

        AttendanceSession session = sessionRepository.findById(f.sessionId()).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.CLOSED);
        assertThat(session.getClosesAt()).isEqualTo(clock.instant());

        mvc.perform(get("/sessions/{id}/qr-token", f.sessionId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.url").value(nullValue()))
                .andExpect(jsonPath("$.svg").value(nullValue()));
        mvc.perform(get("/sessions/{id}/display", f.sessionId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("open", false));
        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(profA()))
                .andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void expiredSessionReportsClosedWithoutTeacherAction() throws Exception {
        Fixture f = fixtureForProfA(null);
        clock.advance(Duration.ofMinutes(15));

        mvc.perform(get("/sessions/{id}/qr-token", f.sessionId()).with(profA()))
                .andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void exportsCsvAttachment() throws Exception {
        Fixture f = fixtureForProfA(null);
        insertRecord(f.sessionId(), insertStudent("sub-1", "Ștefan", "Țărănescu"));
        insertRecord(f.sessionId(), insertStudent("sub-2", "Ana", "Popescu"));

        MvcResult result = mvc.perform(get("/sessions/{id}/export.csv", f.sessionId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, startsWith("attachment")))
                .andReturn();

        ContentDisposition disposition = ContentDisposition.parse(
                result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("CS101_2026-10-16.csv");

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿last_name,first_name,email,checked_in_at\r\n");
        assertThat(csv).contains(
                "Țărănescu,Ștefan,sub-1@student.example.ro,2026-10-16 09:00:05\r\n",
                "Popescu,Ana,sub-2@student.example.ro,2026-10-16 09:00:05\r\n");
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void anotherTeacherGets404ForEverythingOfMine() throws Exception {
        Fixture mine = fixtureForProfA(null);
        AttendanceRecord record = insertRecord(mine.sessionId(), insertStudent("sub-1", "Ana", "Popescu"));
        createTeacher(PROF_B);
        RequestPostProcessor profB = teacherLogin(PROF_B);
        UUID s = mine.sessionId();

        mvc.perform(get("/teacher/courses").with(profB))
                .andExpect(status().isOk())
                .andExpect(model().attribute("courses", empty()));
        mvc.perform(get("/teacher/courses/{id}", mine.courseId()).with(profB)).andExpect(status().isNotFound());
        mvc.perform(post("/teacher/courses/{id}/sessions", mine.courseId()).with(csrf()).with(profB)
                .param("durationMinutes", "15")).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/{id}/display", s).with(profB)).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/{id}/qr-token", s).with(profB)).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/{id}/records", s).with(profB)).andExpect(status().isNotFound());
        mvc.perform(delete("/sessions/{id}/records/{rid}", s, record.getId()).with(csrf()).with(profB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/sessions/{id}/close", s).with(csrf()).with(profB)).andExpect(status().isNotFound());
        mvc.perform(get("/sessions/{id}/export.csv", s).with(profB)).andExpect(status().isNotFound());

        assertThat(recordRepository.existsById(record.getId())).isTrue();
        assertThat(sessionRepository.findById(s).orElseThrow().getStatus()).isEqualTo(SessionStatus.OPEN);
        assertThat(sessionRepository.count()).isEqualTo(1);
    }

    @Test
    void unknownSessionIs404LikeSomeoneElses() throws Exception {
        createTeacher(PROF_A);

        mvc.perform(get("/sessions/{id}/display", UUID.randomUUID()).with(profA()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/teacher/courses/{id}", 999_999).with(profA())).andExpect(status().isNotFound());
    }

    @Test
    void ownershipIsEnforcedInTheServiceLayer() {
        Fixture mine = fixtureForProfA(null);
        Student ana = insertStudent("sub-1", "Ana", "Popescu");
        AttendanceRecord record = insertRecord(mine.sessionId(), ana);
        long otherTeacher = createTeacher(PROF_B).getId();
        UUID s = mine.sessionId();

        assertThatThrownBy(() -> courseService.getOwnedCourse(otherTeacher, mine.courseId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.openSession(otherTeacher, mine.courseId(), 15, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.listSessions(otherTeacher, mine.courseId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.getOwnedSession(otherTeacher, s))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.currentQr(otherTeacher, s))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.close(otherTeacher, s))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.listRecords(otherTeacher, s))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.countRecords(otherTeacher, s))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> recordService.removeRecord(otherTeacher, s, record.getId()))
                .isInstanceOf(NotFoundException.class);

        assertThat(recordService.listRecords(mine.teacherId(), s)).hasSize(1);
        assertThat(courseService.listCourses(otherTeacher)).isEmpty();
        assertThat(recordRepository.existsById(record.getId())).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private Fixture fixtureForProfA(Integer expectedHeadcount) {
        long teacherId = teacherRepository.findByEmail(PROF_A).map(Teacher::getId)
                .orElseGet(() -> createTeacher(PROF_A).getId());
        long courseId = courseService.createCourse(teacherId, "Algoritmi", "CS101").getId();
        UUID sessionId = sessionService.openSession(teacherId, courseId, 15, expectedHeadcount).getId();
        return new Fixture(teacherId, courseId, sessionId);
    }

    private long teacherId(String email) {
        return teacherRepository.findByEmail(email).orElseThrow().getId();
    }

    private static long lastPathSegmentAsLong(String url) {
        return Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
    }
}
