package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanducc.attendance.checkin.Student;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.session.SessionStatus;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import io.github.sanducc.attendance.support.JobTriggerTestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * {@code POST /internal/jobs/daily}, the endpoint Cloud Scheduler calls on the public Cloud Run service (#26): only
 * a Google-signed OIDC token of the scheduler service account for this exact audience runs the jobs. Tokens here
 * are signed with a test key the test context trusts in place of Google's JWKS; the claim checks are production's.
 */
class JobTriggerEndpointTest extends IntegrationTestSupport {

    private static final String PATH = "/internal/jobs/daily";
    /** After the 2025/26 grace period (2026-09-30 + 30 days): sessions opened before 2026-10-01 are due. */
    private static final Instant PURGE_TIME = Instant.parse("2026-11-15T01:30:00Z");

    @Autowired JobTriggerTestTokens tokens;

    private UUID lastYearSession;
    private UUID thisYearSession;
    private Student onlyLastYear;
    private long teacherId;

    @BeforeEach
    void seed() {
        clock.set(Instant.parse("2026-09-10T07:00:00Z"));
        Teacher teacher = createTeacher("prof@university.example.ro");
        teacherId = teacher.getId();
        Course course = courseService.createCourse(teacherId, "Algoritmi", "CS101");
        lastYearSession = sessionService.openSession(teacherId, course.getId(), 15, null).getId();
        onlyLastYear = insertStudent("sub-old", "Ana", "Popescu");
        insertRecord(lastYearSession, onlyLastYear);

        clock.set(Instant.parse("2026-11-14T08:00:00Z"));
        thisYearSession = sessionService.openSession(teacherId, course.getId(), 15, null).getId();
        insertRecord(thisYearSession, insertStudent("sub-new", "Carmen", "Dobre"));

        clock.set(PURGE_TIME);
    }

    // ---------------------------------------------------------------- the scheduler's call

    @Test
    void theSchedulerTokenRunsBothJobsWithoutACsrfToken() throws Exception {
        trigger(tokens.valid(clock.instant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionCutoff").value("2026-09-30T21:00:00Z"))
                .andExpect(jsonPath("$.deletedSessions").value(1))
                .andExpect(jsonPath("$.deletedStudents").value(1))
                .andExpect(jsonPath("$.closedSessions").value(1))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(sessionRepository.existsById(lastYearSession)).isFalse();
        assertThat(studentRepository.existsById(onlyLastYear.getId())).isFalse();
        assertThat(sessionRepository.findById(thisYearSession).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.CLOSED);
        assertThat(recordRepository.countBySessionId(thisYearSession)).isEqualTo(1);
    }

    @Test
    void aSecondCallChangesNothing() throws Exception {
        trigger(tokens.valid(clock.instant())).andExpect(status().isOk());

        trigger(tokens.valid(clock.instant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedSessions").value(0))
                .andExpect(jsonPath("$.deletedStudents").value(0))
                .andExpect(jsonPath("$.closedSessions").value(0));
        assertThat(sessionRepository.count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- rejected calls

    @Test
    void noTokenIsABare401() throws Exception {
        expectRejected(mvc.perform(post(PATH)), 401);
    }

    @Test
    void aNonBearerAuthorizationIsRejected() throws Exception {
        expectRejected(mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Basic not-a-bearer-token")), 401);
    }

    @Test
    void aWrongAudienceIsRejected() throws Exception {
        expectRejected(trigger(tokens.sign(clock.instant(),
                c -> c.audience(List.of("https://qr-attendance-other.europe-west1.run.app/internal/jobs/daily")))), 401);
    }

    @Test
    void anotherServiceAccountIsRejected() throws Exception {
        expectRejected(trigger(tokens.sign(clock.instant(),
                c -> c.claim("email", "someone@other-project.iam.gserviceaccount.com"))), 401);
    }

    @Test
    void anUnverifiedEmailIsRejected() throws Exception {
        expectRejected(trigger(tokens.sign(clock.instant(), c -> c.claim("email_verified", false))), 401);
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String token = tokens.valid(clock.instant().minus(Duration.ofHours(2)));

        expectRejected(trigger(token), 401);
    }

    @Test
    void aForgedTokenIsRejected() throws Exception {
        expectRejected(trigger(JobTriggerTestTokens.generate().valid(clock.instant())), 401);
    }

    @Test
    void anUnsignedTokenIsRejected() throws Exception {
        expectRejected(trigger(JobTriggerTestTokens.unsigned(clock.instant())), 401);
    }

    @Test
    void aTeacherSignedInWithACookieCannotTriggerTheJobs() throws Exception {
        createTeacher("prof.b@university.example.ro");

        MvcResult result = mvc.perform(post(PATH).with(csrf()).with(teacherLogin("prof.b@university.example.ro")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(sessionRepository.existsById(lastYearSession)).as("jobs did not run").isTrue();
    }

    @Test
    void onlyPostIsAllowed() throws Exception {
        expectRejected(mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + tokens.valid(clock.instant()))), 403);
    }

    @Test
    void csrfIsStillEnforcedEverywhereElse() throws Exception {
        createTeacher("prof.c@university.example.ro");

        mvc.perform(post("/teacher/courses").with(teacherLogin("prof.c@university.example.ro"))
                        .param("name", "X").param("code", "X"))
                .andExpect(status().isForbidden());
    }

    @Test
    void callsAreRateLimitedPerIpBeforeTheTokenIsChecked() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        }

        expectRejected(trigger(tokens.valid(clock.instant())), 429);

        clock.advance(Duration.ofMinutes(1));
        trigger(tokens.valid(clock.instant())).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions trigger(String token) throws Exception {
        MockHttpServletRequestBuilder request = post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return mvc.perform(request);
    }

    /** The status, no body, no hint why, and the jobs didn't run. */
    private void expectRejected(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        assertThat(sessionRepository.existsById(lastYearSession)).as("jobs did not run").isTrue();
    }
}
