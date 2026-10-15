package io.github.sanducc.attendance.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.sanducc.attendance.checkin.AttendanceRecord;
import io.github.sanducc.attendance.checkin.AttendanceRecordRepository;
import io.github.sanducc.attendance.checkin.PendingCheckIn;
import io.github.sanducc.attendance.checkin.Provider;
import io.github.sanducc.attendance.checkin.Student;
import io.github.sanducc.attendance.checkin.StudentRepository;
import io.github.sanducc.attendance.common.DeviceIdFilter;
import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.config.AppOidcUserService;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.CourseService;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.course.TeacherRepository;
import io.github.sanducc.attendance.session.AttendanceSessionRepository;
import io.github.sanducc.attendance.session.QrTokenService;
import io.github.sanducc.attendance.session.SessionService;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for Testcontainers-backed integration tests. Skipped automatically when Docker isn't
 * available (local Windows dev); runs in CI. Every test starts from empty tables, an empty
 * Redis and the clock at {@link MutableClock#DEFAULT_START}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
public abstract class IntegrationTestSupport {

    protected static final String RAW_TEST_IP = "127.0.0.1";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired protected MockMvc mvc;
    @Autowired protected MutableClock clock;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired protected ClientRegistrationRepository clientRegistrations;
    @Autowired protected TeacherRepository teacherRepository;
    @Autowired protected CourseService courseService;
    @Autowired protected SessionService sessionService;
    @Autowired protected QrTokenService qrTokens;
    @Autowired protected StudentRepository studentRepository;
    @Autowired protected AttendanceRecordRepository recordRepository;
    @Autowired protected AttendanceSessionRepository sessionRepository;
    @Autowired protected PepperedHasher hasher;

    @BeforeEach
    void resetSharedState() {
        clock.reset();
        jdbc.execute("truncate attendance_record, student, attendance_session, course, teacher restart identity cascade");
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    // ---------------------------------------------------------------- sign-in

    /** A signed-in Google user with only the student role (everyone who signs in is a student). */
    protected RequestPostProcessor studentLogin(String subject, String givenName, String familyName) {
        return login(subject, subject + "@student.example.ro", givenName, familyName,
                AppOidcUserService.ROLE_STUDENT);
    }

    /** A signed-in, allowlisted teacher. The teacher row must exist (see {@link #createTeacher}). */
    protected RequestPostProcessor teacherLogin(String email) {
        return login("sub-" + email, email, "Prof", "Teacher",
                AppOidcUserService.ROLE_STUDENT, AppOidcUserService.ROLE_TEACHER);
    }

    protected RequestPostProcessor login(String subject, String email, String givenName, String familyName,
                                         String... roles) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority(role));
        }
        // The app maps the registration id to a Provider and rejects unknown ids, so use the real one.
        return oidcLogin()
                .clientRegistration(google())
                .idToken(token -> {
                    token.subject(subject);
                    token.claim("email", email);
                    token.claim("email_verified", true);
                    if (givenName != null) {
                        token.claim("given_name", givenName);
                    }
                    if (familyName != null) {
                        token.claim("family_name", familyName);
                    }
                })
                .authorities(authorities);
    }

    protected ClientRegistration google() {
        ClientRegistration registration = clientRegistrations.findByRegistrationId("google");
        if (registration == null) {
            throw new IllegalStateException("No 'google' client registration in the test context");
        }
        return registration;
    }

    // ---------------------------------------------------------------- fixtures

    public record Fixture(long teacherId, long courseId, UUID sessionId) {}

    /** Teacher + course "Algoritmi" (CS101) + one open 15-minute session, all at the current clock time. */
    protected Fixture openFixture() {
        return openFixture(null);
    }

    protected Fixture openFixture(Integer expectedHeadcount) {
        Teacher teacher = createTeacher("prof" + SEQUENCE.incrementAndGet() + "@university.example.ro");
        Course course = courseService.createCourse(teacher.getId(), "Algoritmi", "CS101");
        UUID sessionId = sessionService.openSession(teacher.getId(), course.getId(), 15, expectedHeadcount).getId();
        return new Fixture(teacher.getId(), course.getId(), sessionId);
    }

    protected Teacher createTeacher(String email) {
        return teacherRepository.save(new Teacher(email.toLowerCase(), "Prof " + email, clock.instant()));
    }

    protected Student insertStudent(String subject, String firstName, String lastName) {
        return studentRepository.saveAndFlush(new Student(Provider.GOOGLE, subject, subject + "@student.example.ro",
                firstName, lastName, clock.instant()));
    }

    protected AttendanceRecord insertRecord(UUID sessionId, Student student) {
        String deviceHash = hasher.hash("device", "device-of-" + student.getProviderSubject());
        return recordRepository.saveAndFlush(new AttendanceRecord(sessionId, student.getId(), clock.instant(),
                deviceHash, hasher.hash("ip", RAW_TEST_IP), "JUnit"));
    }

    protected String validToken(UUID sessionId) {
        return qrTokens.current(sessionId).value();
    }

    protected Optional<Student> findStudent(String subject) {
        return studentRepository.findByProviderAndProviderSubject(Provider.GOOGLE, subject);
    }

    // ---------------------------------------------------------------- browser

    protected Browser newBrowser() {
        return new Browser(mvc, new MockHttpSession(), null);
    }

    /** Scans the QR as this browser (anonymous unless the browser's session already holds a login). */
    protected ResultActions scan(Browser browser, UUID sessionId, String token) throws Exception {
        return browser.perform(get("/a/{id}", sessionId).param("t", token));
    }

    protected ResultActions confirm(Browser browser, UUID sessionId, RequestPostProcessor login) throws Exception {
        return browser.perform(get("/a/{id}/confirm", sessionId).with(login));
    }

    protected ResultActions postCheckIn(Browser browser, UUID sessionId, RequestPostProcessor login,
                                        String firstName, String lastName) throws Exception {
        MockHttpServletRequestBuilder request = post("/a/{id}/check-in", sessionId).with(csrf()).with(login);
        if (firstName != null) {
            request.param("firstName", firstName);
        }
        if (lastName != null) {
            request.param("lastName", lastName);
        }
        return browser.perform(request);
    }

    protected ResultActions done(Browser browser, UUID sessionId, RequestPostProcessor login) throws Exception {
        return browser.perform(get("/a/{id}/done", sessionId).with(login));
    }

    /**
     * One phone/browser: an HTTP session plus the {@code device_id} cookie captured from the first
     * /a/** response. Two Browser objects with different cookies are two different phones.
     */
    public static final class Browser {

        private final MockMvc mvc;
        private final MockHttpSession session;
        private Cookie deviceCookie;

        private Browser(MockMvc mvc, MockHttpSession session, Cookie deviceCookie) {
            this.mvc = mvc;
            this.session = session;
            this.deviceCookie = deviceCookie;
        }

        public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
            request.session(session);
            if (deviceCookie != null) {
                request.cookie(deviceCookie);
            }
            ResultActions result = mvc.perform(request);
            Cookie issued = result.andReturn().getResponse().getCookie(DeviceIdFilter.COOKIE_NAME);
            if (issued != null) {
                deviceCookie = new Cookie(issued.getName(), issued.getValue());
            }
            return result;
        }

        /** Same phone (same device cookie), fresh HTTP session: e.g. after signing out and in as someone else. */
        public Browser sameDeviceNewSession() {
            return new Browser(mvc, new MockHttpSession(), deviceCookie);
        }

        /** This browser's HTTP session but another phone's device cookie. */
        public Browser withDeviceCookieOf(Browser other) {
            return new Browser(mvc, session, other.deviceCookie);
        }

        public MockHttpSession session() {
            return session;
        }

        public Cookie deviceCookie() {
            return deviceCookie;
        }

        /** The raw device id inside the signed cookie ({@code <id>.<signature>}). */
        public String rawDeviceId() {
            if (deviceCookie == null) {
                return null;
            }
            String value = deviceCookie.getValue();
            return value.substring(0, value.indexOf('.'));
        }

        public PendingCheckIn pending() {
            return PendingCheckIn.from(session);
        }
    }
}
