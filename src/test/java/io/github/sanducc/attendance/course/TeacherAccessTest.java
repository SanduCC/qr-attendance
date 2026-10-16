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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
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
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.SessionStatus;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    @Autowired AttendanceProperties properties;

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
                .andExpect(redirectedUrlPattern("/teacher/courses#course-*"))
                .andExpect(flash().attribute("message", "Course created"))
                .andReturn();
        long courseId = createdCourseId(created);
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

    /**
     * #30 reverses #19 at the owner's request: a name is enough, the code is optional (blank is stored as null), and
     * the form says so. Creating a course stays on the courses page, with a flash and the new card highlighted.
     */
    @Test
    void courseCodeIsOptionalButTheNameIsRequired() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();

        MvcResult created = mvc.perform(post("/teacher/courses").with(csrf()).with(profA())
                        .param("name", "Algoritmi").param("code", "  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/teacher/courses#course-*"))
                .andExpect(flash().attribute("message", "Course created"))
                .andExpect(flash().attributeExists("createdCourseId"))
                .andReturn();
        long courseId = createdCourseId(created);
        Course course = courseService.getOwnedCourse(teacherId, courseId);
        assertThat(course.getName()).isEqualTo("Algoritmi");
        assertThat(course.getCode()).isNull();

        mvc.perform(post("/teacher/courses").with(csrf()).with(profA()).param("name", "Baze de date"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/teacher/courses#course-*"));

        mvc.perform(post("/teacher/courses").with(csrf()).with(profA()).param("name", " ").param("code", "CS101"))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/courses"))
                .andExpect(model().attributeHasFieldErrors("courseForm", "name"))
                .andExpect(model().attribute("showNewCourse", true));
        mvc.perform(post("/teacher/courses").with(csrf()).with(profA())
                        .param("name", "Algoritmi 2").param("code", "X".repeat(51)))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("courseForm", "code"));
        assertThat(courseService.listCourses(teacherId)).hasSize(2);

        // The page after the redirect: flash banner, highlighted card, a course without a code renders fine.
        String html = mvc.perform(get("/teacher/courses").with(profA())
                        .flashAttr("message", "Course created").flashAttr("createdCourseId", courseId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Course created", "id=\"course-" + courseId + "\"", "is-new", "No sessions yet");
        // Thymeleaf writes id/name/value after the static attributes, so match the tag regardless of order.
        assertThat(inputTag(html, "name")).containsPattern("\\srequired[\\s>=/]");
        assertThat(inputTag(html, "code")).doesNotContainPattern("\\srequired[\\s>=/]");
        assertThat(html).contains("(optional)");

        mvc.perform(get("/teacher/courses/{id}", courseId).with(profA()))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/course"));
    }

    // ------------------------------------------------------------------ one-click start (#30)

    @Test
    void oneClickStartUsesTheDefaultDurationAndTheCoursesLastExpectedHeadcount() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();
        long courseId = courseService.createCourse(teacherId, "Algoritmi", null).getId();
        Duration defaultDuration = properties.session().defaultDuration();

        // No earlier session: no expected headcount.
        AttendanceSession first = sessionRepository.findById(startOneClick(courseId)).orElseThrow();
        assertThat(first.getExpectedHeadcount()).isNull();
        assertThat(first.getClosesAt()).isEqualTo(first.getOpenedAt().plus(defaultDuration));

        // With options: exactly what the teacher entered.
        clock.advance(Duration.ofDays(1));
        AttendanceSession withOptions = sessionRepository.findById(start(courseId, "20", "25")).orElseThrow();
        assertThat(withOptions.getExpectedHeadcount()).isEqualTo(25);
        assertThat(withOptions.getClosesAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(20)));

        // One click again: default duration, the last session's headcount.
        clock.advance(Duration.ofDays(1));
        AttendanceSession again = sessionRepository.findById(startOneClick(courseId)).orElseThrow();
        assertThat(again.getExpectedHeadcount()).isEqualTo(25);
        assertThat(again.getClosesAt()).isEqualTo(clock.instant().plus(defaultDuration));

        // Clearing the field in the options means "no headcount", not "same as last time"...
        clock.advance(Duration.ofDays(1));
        AttendanceSession cleared = sessionRepository.findById(start(courseId, "30", "")).orElseThrow();
        assertThat(cleared.getExpectedHeadcount()).isNull();
        assertThat(cleared.getClosesAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));

        // ...and the next one-click start follows that most recent session.
        clock.advance(Duration.ofDays(1));
        assertThat(sessionRepository.findById(startOneClick(courseId)).orElseThrow().getExpectedHeadcount()).isNull();

        // The course page pre-fills the options with those defaults.
        mvc.perform(get("/teacher/courses/{id}", courseId).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sessions", hasSize(5)))
                .andExpect(model().attribute("lastExpectedHeadcount", nullValue()))
                .andExpect(model().attribute("showOptions", false));
    }

    @Test
    void startWithOptionsStillValidatesTheDuration() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();
        long courseId = courseService.createCourse(teacherId, "Algoritmi", "CS101").getId();
        long maxMinutes = properties.session().maxDuration().toMinutes();

        for (String bad : new String[] {"0", "-5", String.valueOf(maxMinutes + 1)}) {
            mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                            .param("durationMinutes", bad).param("headcountExplicit", "true"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("teacher/course"))
                    .andExpect(model().attributeHasFieldErrors("openForm", "durationMinutes"))
                    .andExpect(model().attribute("showOptions", true));
        }
        mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                        .param("expectedHeadcount", "0").param("headcountExplicit", "true"))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("openForm", "expectedHeadcount"));
        assertThat(sessionRepository.count()).isZero();

        mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                        .param("durationMinutes", String.valueOf(maxMinutes)).param("headcountExplicit", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/sessions/*/display"));
    }

    @Test
    void coursesPageShowsEachCoursesLastSessionAndTheCoursePageLinksTheExports() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();
        long algoritmi = courseService.createCourse(teacherId, "Algoritmi", "CS101").getId();
        courseService.createCourse(teacherId, "Baze de date", null);
        Student ana = insertStudent("sub-1", "Ana", "Popescu");
        Student bogdan = insertStudent("sub-2", "Bogdan", "Ionescu");

        UUID lastWeek = sessionService.openSession(teacherId, algoritmi, 15, 30).getId();
        insertRecord(lastWeek, ana);
        clock.advance(Duration.ofDays(7));
        UUID today = sessionService.openSession(teacherId, algoritmi, 15, 25).getId();
        insertRecord(today, ana);
        insertRecord(today, bogdan);

        MvcResult result = mvc.perform(get("/teacher/courses").with(profA()))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/courses"))
                .andExpect(model().attribute("showNewCourse", false))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<CourseController.CourseCard> cards =
                (List<CourseController.CourseCard>) result.getModelAndView().getModel().get("courses");
        assertThat(cards).extracting(CourseController.CourseCard::name).containsExactly("Algoritmi", "Baze de date");

        CourseController.CourseCard withSessions = cards.get(0);
        assertThat(withSessions.code()).isEqualTo("CS101");
        assertThat(withSessions.lastSession().id()).isEqualTo(today);
        assertThat(withSessions.lastSession().checkedIn()).isEqualTo(2);
        assertThat(withSessions.lastSession().open()).isTrue();
        assertThat(withSessions.nextExpectedHeadcount()).isEqualTo(25);

        CourseController.CourseCard empty = cards.get(1);
        assertThat(empty.code()).isNull();
        assertThat(empty.lastSession()).isNull();
        assertThat(empty.nextExpectedHeadcount()).isNull();

        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Start attendance", "No sessions yet", "2 of 25 checked in",
                "/sessions/" + today + "/display", "/teacher/courses/" + algoritmi + "/sessions");

        String coursePage = mvc.perform(get("/teacher/courses/{id}", algoritmi).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("sessions", hasSize(2)))
                .andExpect(model().attribute("lastExpectedHeadcount", 25))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(coursePage).contains(
                "/sessions/" + today + "/export.xlsx", "/sessions/" + today + "/export.csv",
                "/sessions/" + lastWeek + "/export.xlsx", "/sessions/" + lastWeek + "/display", "1 / 30", "2 / 25");
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
                .andExpect(jsonPath("$.records[1].checkedInAt").value("09:00:12"))
                // The projector's countdown runs on these (server time, whatever the laptop clock says).
                .andExpect(jsonPath("$.closesAtMillis")
                        .value(sessionRepository.findById(f.sessionId()).orElseThrow().getClosesAt().toEpochMilli()))
                .andExpect(jsonPath("$.serverTimeMillis").value(clock.millis()));

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
        String display = mvc.perform(get("/sessions/{id}/display", f.sessionId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("open", false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Closed state: no close button, the downloads instead.
        assertThat(display).doesNotContain("id=\"close-form\"")
                .contains("Download Excel", "/sessions/" + f.sessionId() + "/export.xlsx");
        mvc.perform(get("/sessions/{id}/records", f.sessionId()).with(profA()))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.closesAtMillis").value(clock.millis()));
    }

    @Test
    void displayPageCarriesTheCountdownStartingPoint() throws Exception {
        Fixture f = fixtureForProfA(25);
        AttendanceSession session = sessionRepository.findById(f.sessionId()).orElseThrow();
        clock.advance(Duration.ofMinutes(3));

        String html = mvc.perform(get("/sessions/{id}/display", f.sessionId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("closesAtMillis", session.getClosesAt().toEpochMilli()))
                .andExpect(model().attribute("serverTimeMillis", clock.millis()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("data-closes-at-millis=\"" + session.getClosesAt().toEpochMilli() + "\"",
                "data-server-time-millis=\"" + clock.millis() + "\"", "id=\"close-form\"", "id=\"fullscreen-btn\"",
                "id=\"countdown\"");
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
        // One click (no fields) must not reveal or reuse someone else's course either.
        mvc.perform(post("/teacher/courses/{id}/sessions", mine.courseId()).with(csrf()).with(profB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/teacher/courses/{id}/sessions", mine.courseId()).with(csrf()).with(profB)
                .param("durationMinutes", "0")).andExpect(status().isNotFound());
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
        assertThatThrownBy(() -> sessionService.startSession(otherTeacher, mine.courseId(), null, null, false))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.lastExpectedHeadcount(otherTeacher, mine.courseId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.listSessions(otherTeacher, mine.courseId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> sessionService.listSessionSummaries(otherTeacher, mine.courseId()))
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
        assertThat(sessionService.listCourseSummaries(otherTeacher)).isEmpty();
        assertThat(sessionService.listCourseSummaries(mine.teacherId())).hasSize(1);
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

    /** One click: POST with no fields at all, like the "Start attendance" button. */
    private UUID startOneClick(long courseId) throws Exception {
        return sessionIdFrom(mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/sessions/*/display"))
                .andReturn());
    }

    /** The options form: always sends both fields (possibly empty) plus headcountExplicit. */
    private UUID start(long courseId, String durationMinutes, String expectedHeadcount) throws Exception {
        return sessionIdFrom(mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                        .param("headcountExplicit", "true")
                        .param("durationMinutes", durationMinutes)
                        .param("expectedHeadcount", expectedHeadcount))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/sessions/*/display"))
                .andReturn());
    }

    private static UUID sessionIdFrom(MvcResult result) {
        return UUID.fromString(result.getResponse().getRedirectedUrl().split("/")[2]);
    }

    private static long createdCourseId(MvcResult result) {
        String url = result.getResponse().getRedirectedUrl();
        return Long.parseLong(url.substring(url.indexOf("#course-") + "#course-".length()));
    }

    /** The {@code <input>} tag for a field, whatever order Thymeleaf writes the attributes in. */
    private static String inputTag(String html, String name) {
        Matcher input = Pattern.compile("<input[^>]*\\bname=\"" + name + "\"[^>]*>").matcher(html);
        assertThat(input.find()).as(name + " input rendered").isTrue();
        return input.group();
    }
}
