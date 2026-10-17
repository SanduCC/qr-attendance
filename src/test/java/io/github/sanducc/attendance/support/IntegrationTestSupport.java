package io.github.sanducc.attendance.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import io.github.sanducc.attendance.config.session.ResilientSessionRepository;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base for Testcontainers-backed integration tests. Skipped automatically when Docker isn't
 * available (local Windows dev); runs in CI. Every test starts from empty tables, an empty
 * Redis (so no HTTP sessions) and the clock at {@link MutableClock#DEFAULT_START}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class, FakeGoogleConfiguration.class,
        JobTriggerTestConfiguration.class})
public abstract class IntegrationTestSupport {

    protected static final String RAW_TEST_IP = "127.0.0.1";
    /** Spring Session's cookie (default name; it replaced the servlet container's JSESSIONID). */
    protected static final String SESSION_COOKIE = "SESSION";

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
    @Autowired protected ResilientSessionRepository httpSessions;
    /** The app's CSRF token store (SecurityConfig#csrfTokenRepository). */
    @Autowired protected CsrfTokenRepository csrfTokenRepository;
    @Autowired private List<SecurityFilterChain> securityFilterChains;

    @BeforeEach
    void resetSharedState() {
        clock.reset();
        restoreCsrfTokenRepository();
        jdbc.execute("truncate attendance_record, student, attendance_session, course, teacher restart identity cascade");
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    /**
     * {@code SecurityMockMvcRequestPostProcessors.csrf()} replaces the {@link CsrfFilter}'s token repository with a
     * test one wrapping a plain {@code HttpSessionCsrfTokenRepository}, for good: the filter lives in the cached
     * application context. Put the app's repository back on the browser chain (the catch-all one) so every test,
     * and in particular every request without {@code csrf()}, runs with the production wiring (issue #47).
     */
    private void restoreCsrfTokenRepository() {
        int restored = 0;
        for (SecurityFilterChain chain : securityFilterChains) {
            if (chain instanceof DefaultSecurityFilterChain browserChain
                    && browserChain.getRequestMatcher() instanceof AnyRequestMatcher) {
                for (var filter : chain.getFilters()) {
                    if (filter instanceof CsrfFilter csrfFilter) {
                        ReflectionTestUtils.setField(csrfFilter, "tokenRepository", csrfTokenRepository);
                        restored++;
                    }
                }
            }
        }
        assertThat(restored).as("CsrfFilter of the browser filter chain").isEqualTo(1);
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

    /** A student signed in with a Google Workspace account: the ID token carries {@code hd} (null = consumer). */
    protected RequestPostProcessor workspaceStudentLogin(String subject, String email, String hostedDomain) {
        return oidcLogin()
                .clientRegistration(google())
                .idToken(token -> {
                    token.subject(subject);
                    token.claim("email", email);
                    token.claim("email_verified", true);
                    token.claim("given_name", "Ana");
                    token.claim("family_name", "Popescu");
                    if (hostedDomain != null) {
                        token.claim("hd", hostedDomain);
                    }
                })
                .authorities(new SimpleGrantedAuthority(AppOidcUserService.ROLE_STUDENT));
    }

    /**
     * Runs the real OAuth2 login ({@link FakeGoogleConfiguration} plays Google): the redirect to "Google", then the
     * callback with the matching state. {@code hostedDomain} becomes the ID token's {@code hd} claim (null = a
     * consumer account without one).
     */
    protected ResultActions signInWithGoogle(Browser browser, String email, String hostedDomain) throws Exception {
        String location = authorizationRedirect(browser);
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        assertThat(state).isNotBlank();
        String decodedState = URLDecoder.decode(state, StandardCharsets.UTF_8);
        return browser.perform(get("/login/oauth2/code/google")
                .param("code", FakeGoogleConfiguration.code(email, hostedDomain))
                .param("state", decodedState));
    }

    protected ResultActions signInWithGoogle(Browser browser, String email) throws Exception {
        return signInWithGoogle(browser, email, null);
    }

    /** Starts the OAuth2 login and returns where the app sends the browser ("Google"'s authorization URL). */
    protected String authorizationRedirect(Browser browser) throws Exception {
        MvcResult authorize = browser.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("https://accounts.google.com/")))
                .andReturn();
        return authorize.getResponse().getHeader(HttpHeaders.LOCATION);
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
        return new Browser(mvc, httpSessions, new SessionCookie(), null);
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

    /** The check-in POST with what checkin-location.js would send on a session with a location check. */
    protected ResultActions postCheckInWithLocation(Browser browser, UUID sessionId, RequestPostProcessor login,
                                                    String firstName, String lastName, String latitude,
                                                    String longitude, String accuracy) throws Exception {
        MockHttpServletRequestBuilder request = post("/a/{id}/check-in", sessionId).with(csrf()).with(login)
                .param("firstName", firstName).param("lastName", lastName);
        if (latitude != null) {
            request.param("latitude", latitude);
        }
        if (longitude != null) {
            request.param("longitude", longitude);
        }
        if (accuracy != null) {
            request.param("accuracy", accuracy);
        }
        return browser.perform(request);
    }

    protected ResultActions done(Browser browser, UUID sessionId, RequestPostProcessor login) throws Exception {
        return browser.perform(get("/a/{id}/done", sessionId).with(login));
    }

    /**
     * One phone/browser: its Spring Session cookie ({@code SESSION}) plus the {@code device_id} cookie captured
     * from the first /a/** response, both replayed on every request like a real browser. Two Browser objects
     * with different cookies are two different phones.
     */
    public static final class Browser {

        private final MockMvc mvc;
        private final ResilientSessionRepository sessions;
        private final SessionCookie sessionCookie;
        private Cookie deviceCookie;

        private Browser(MockMvc mvc, ResilientSessionRepository sessions, SessionCookie sessionCookie,
                        Cookie deviceCookie) {
            this.mvc = mvc;
            this.sessions = sessions;
            this.sessionCookie = sessionCookie;
            this.deviceCookie = deviceCookie;
        }

        public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
            if (sessionCookie.value != null) {
                request.cookie(sessionCookie.value);
            }
            if (deviceCookie != null) {
                request.cookie(deviceCookie);
            }
            ResultActions result = mvc.perform(request);
            MockHttpServletResponse response = result.andReturn().getResponse();
            Cookie issuedDevice = response.getCookie(DeviceIdFilter.COOKIE_NAME);
            if (issuedDevice != null) {
                deviceCookie = new Cookie(issuedDevice.getName(), issuedDevice.getValue());
            }
            Cookie issuedSession = response.getCookie(SESSION_COOKIE);
            if (issuedSession != null) {
                boolean expired = issuedSession.getMaxAge() == 0 || issuedSession.getValue() == null
                        || issuedSession.getValue().isEmpty();
                sessionCookie.value = expired ? null : new Cookie(SESSION_COOKIE, issuedSession.getValue());
            }
            return result;
        }

        /** Same phone (same device cookie), fresh HTTP session: e.g. after signing out and in as someone else. */
        public Browser sameDeviceNewSession() {
            return new Browser(mvc, sessions, new SessionCookie(), deviceCookie);
        }

        /** This browser's HTTP session but another phone's device cookie. */
        public Browser withDeviceCookieOf(Browser other) {
            return new Browser(mvc, sessions, sessionCookie, other.deviceCookie);
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

        /** The HTTP session id (the cookie carries it Base64-encoded), or null without a session. */
        public String sessionId() {
            return sessionCookie.value == null ? null
                    : new String(Base64.getDecoder().decode(sessionCookie.value.getValue()), StandardCharsets.UTF_8);
        }

        /** This browser's HTTP session as stored in Redis, or null. */
        public Session storedSession() {
            String id = sessionId();
            return id == null ? null : sessions.findById(id);
        }

        public PendingCheckIn pending() {
            Session session = storedSession();
            return session == null ? null
                    : (session.getAttribute(PendingCheckIn.SESSION_KEY) instanceof PendingCheckIn p ? p : null);
        }
    }

    /** Shared by Browser views of the same HTTP session, so an id rotated through one is seen by all. */
    private static final class SessionCookie {
        private Cookie value;
    }
}
