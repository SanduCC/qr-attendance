package io.github.sanducc.attendance.config.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.checkin.PendingCheckIn;
import io.github.sanducc.attendance.support.FakeGoogleConfiguration;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP sessions in Redis (issue #7), with the real OAuth2 login flow (Google faked by
 * {@link FakeGoogleConfiguration}): the session really lives in Redis, its id rotates on sign-in, it survives a
 * "restart", sign-out deletes it, and a session the app can't read back means "sign in again", never a 500.
 */
class RedisHttpSessionTest extends IntegrationTestSupport {

    private static final String KEY_PREFIX = SessionConfig.NAMESPACE + ":sessions:";
    private static final String SECURITY_CONTEXT_FIELD =
            "sessionAttr:" + HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"");

    private static final String ANA = "ana@student.example.ro";

    // ------------------------------------------------------------------ the flow, end to end

    @Test
    void scanSignInConfirmCheckInWorksWithTheRedisStore() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        // Anonymous scan: a new session, in Redis, holding the pending check-in.
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        String scanSessionId = phone.sessionId();
        assertThat(scanSessionId).isNotNull();
        assertThat(redis.hasKey(KEY_PREFIX + scanSessionId)).isTrue();
        PendingCheckIn pending = phone.pending();
        assertThat(pending).isNotNull();
        assertThat(pending.sessionId()).isEqualTo(f.sessionId());

        signInWithGoogle(phone, ANA)
                .andExpect(redirectedUrl("/a/" + f.sessionId() + "/confirm"));

        // Session fixation: the id rotated on sign-in and the pre-login id is gone from Redis.
        String signedInSessionId = phone.sessionId();
        assertThat(signedInSessionId).isNotNull().isNotEqualTo(scanSessionId);
        assertThat(redis.hasKey(KEY_PREFIX + scanSessionId)).isFalse();
        assertThat(redis.hasKey(KEY_PREFIX + signedInSessionId)).isTrue();
        // The pending check-in moved along with the session; the device binding is unchanged.
        assertThat(phone.pending()).isEqualTo(pending);

        // From here on the sign-in comes only from the Redis session (no oidcLogin() post-processor).
        MvcResult confirmPage = phone.perform(get("/a/{id}/confirm", f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(view().name("student/confirm"))
                .andReturn();

        phone.perform(post("/a/{id}/check-in", f.sessionId())
                        .param("_csrf", csrfToken(confirmPage))
                        .param("firstName", "Ana").param("lastName", "Popescu"))
                .andExpect(redirectedUrl("/a/" + f.sessionId() + "/done"));
        phone.perform(get("/a/{id}/done", f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(view().name("student/done"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
        assertThat(findStudent("sub-" + ANA)).isPresent();
        assertThat(phone.pending()).as("pending scan consumed").isNull();
    }

    @Test
    void aSessionSurvivesARestartAndSignOutDeletesIt() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithGoogle(phone, ANA).andExpect(status().is3xxRedirection());
        String sessionId = phone.sessionId();

        // "Restart": a brand-new repository (as a freshly deployed instance would build) reads what was stored.
        RedisSessionRepository afterRestart = SessionConfig.redisSessionRepository(
                redis.getConnectionFactory(), Duration.ofMinutes(60));
        Session restored = afterRestart.findById(sessionId);
        assertThat(restored).isNotNull();
        SecurityContext context = restored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context.getAuthentication()).isInstanceOf(OAuth2AuthenticationToken.class);
        assertThat(((OidcUser) context.getAuthentication().getPrincipal()).getSubject()).isEqualTo("sub-" + ANA);
        assertThat(restored.<PendingCheckIn>getAttribute(PendingCheckIn.SESSION_KEY).sessionId())
                .isEqualTo(f.sessionId());

        // The same cookie keeps working against the store.
        MvcResult confirmPage = phone.perform(get("/a/{id}/confirm", f.sessionId()))
                .andExpect(status().isOk())
                .andReturn();

        // Sign out: the Redis session is deleted and the cookie cleared.
        phone.perform(post("/logout").param("_csrf", csrfToken(confirmPage)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
        assertThat(redis.hasKey(KEY_PREFIX + sessionId)).isFalse();
        assertThat(phone.sessionId()).isNull();
        assertThat(afterRestart.findById(sessionId)).isNull();

        // Replaying the old cookie doesn't bring the sign-in back.
        newBrowser().perform(get("/a/{id}/confirm", f.sessionId())
                        .cookie(new Cookie(SESSION_COOKIE, encodedId(sessionId))))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
    }

    @Test
    void sessionKeysUseTheirOwnNamespaceWithAnExpiry() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());

        assertThat(redis.keys(KEY_PREFIX + "*")).containsExactly(KEY_PREFIX + phone.sessionId());
        assertThat(redis.keys("rl:*")).isNotEmpty().noneMatch(k -> k.startsWith(SessionConfig.NAMESPACE));
        Long ttlSeconds = redis.getExpire(KEY_PREFIX + phone.sessionId());
        assertThat(ttlSeconds).isBetween(Duration.ofMinutes(59).toSeconds(), Duration.ofMinutes(60).toSeconds());
    }

    @Test
    void readOnlyPollingDoesNotRewriteTheSession() throws Exception {
        Fixture f = openFixture();
        String teacherEmail = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        Browser laptop = newBrowser();
        signInWithGoogle(laptop, teacherEmail).andExpect(redirectedUrl("/teacher/courses"));
        laptop.perform(get("/sessions/{id}/display", f.sessionId())).andExpect(status().isOk());
        String key = KEY_PREFIX + laptop.sessionId();
        byte[] lastAccessBefore = hashField(key, "lastAccessedTime");
        assertThat(lastAccessBefore).isNotNull();

        for (int i = 0; i < 3; i++) {
            laptop.perform(get("/sessions/{id}/records", f.sessionId())).andExpect(status().isOk());
            laptop.perform(get("/sessions/{id}/qr-token", f.sessionId())).andExpect(status().isOk());
        }

        assertThat(hashField(key, "lastAccessedTime")).as("no write within the touch interval")
                .isEqualTo(lastAccessBefore);
    }

    // ------------------------------------------------------------------ version-to-version safety

    @Test
    void anUnreadableSecurityContextMeansSignInAgainNotA500() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithGoogle(phone, ANA).andExpect(status().is3xxRedirection());
        String key = KEY_PREFIX + phone.sessionId();

        // As if an older/newer version of the app (or a Spring upgrade) had written a class we can't read.
        setHashField(key, SECURITY_CONTEXT_FIELD, "\u00ac\u00ed\u0000\u0005garbage".getBytes(StandardCharsets.ISO_8859_1));

        phone.perform(get("/a/{id}/confirm", f.sessionId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        assertThat(redis.hasKey(key)).as("broken session deleted").isFalse();

        // Not a loop: the browser now has a fresh, readable session and can scan and sign in again.
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        signInWithGoogle(phone, ANA).andExpect(redirectedUrl("/a/" + f.sessionId() + "/confirm"));
        phone.perform(get("/a/{id}/confirm", f.sessionId())).andExpect(status().isOk());
    }

    @Test
    void aForeignClassInTheSessionIsRejectedAndTheSessionDropped() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        String key = KEY_PREFIX + phone.sessionId();

        // A class outside the deserialization allowlist (a classic gadget-chain entry point).
        setHashField(key, "sessionAttr:injected", jdkSerialize(new javax.management.BadAttributeValueExpException("x")));

        phone.perform(get("/a/{id}/confirm", f.sessionId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void aCorruptMandatoryFieldMeansNoSessionNotA500() throws Exception {
        Fixture f = openFixture();
        String teacherEmail = teacherRepository.findById(f.teacherId()).orElseThrow().getEmail();
        Browser laptop = newBrowser();
        signInWithGoogle(laptop, teacherEmail).andExpect(redirectedUrl("/teacher/courses"));
        String key = KEY_PREFIX + laptop.sessionId();

        setHashField(key, "creationTime", "not a java object".getBytes(StandardCharsets.UTF_8));

        laptop.perform(get("/teacher/courses"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/login")));
        assertThat(redis.hasKey(key)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the real OAuth2 login: redirect to "Google", then the callback with the matching state. */
    private ResultActions signInWithGoogle(Browser browser, String email)
            throws Exception {
        MvcResult authorize = browser.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("https://accounts.google.com/")))
                .andReturn();
        String location = authorize.getResponse().getHeader(HttpHeaders.LOCATION);
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        assertThat(state).isNotBlank();
        String decodedState = URLDecoder.decode(state, StandardCharsets.UTF_8);
        return browser.perform(get("/login/oauth2/code/google").param("code", email).param("state", decodedState));
    }

    private static String csrfToken(MvcResult page) throws Exception {
        Matcher m = CSRF_INPUT.matcher(page.getResponse().getContentAsString());
        assertThat(m.find()).as("page has a CSRF field").isTrue();
        return m.group(1);
    }

    private static String encodedId(String sessionId) {
        return Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hashField(String key, String field) {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            return connection.hashCommands().hGet(bytes(key), bytes(field));
        }
    }

    private void setHashField(String key, String field, byte[] value) {
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.hashCommands().hSet(bytes(key), bytes(field), value);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jdkSerialize(Object value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(value);
        }
        return out.toByteArray();
    }
}
