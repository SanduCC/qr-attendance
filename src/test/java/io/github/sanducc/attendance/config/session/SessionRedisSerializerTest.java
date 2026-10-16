package io.github.sanducc.attendance.config.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanducc.attendance.checkin.PendingCheckIn;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.management.BadAttributeValueExpException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.servlet.FlashMap;

/** What the HTTP session holds must round-trip; anything unreadable or foreign comes back as UNREADABLE. */
class SessionRedisSerializerTest {

    private final SessionRedisSerializer serializer = new SessionRedisSerializer();

    private Object roundTrip(Object value) {
        return serializer.deserialize(serializer.serialize(value));
    }

    // ------------------------------------------------------------------ everything a session holds

    @Test
    void ourOwnSessionValuesRoundTrip() {
        PendingCheckIn pending = new PendingCheckIn(UUID.randomUUID(), Instant.parse("2026-10-16T06:00:05Z"),
                "a".repeat(64));
        assertThat(roundTrip(pending)).isEqualTo(pending);
        assertThat(roundTrip(Boolean.TRUE)).isEqualTo(true);
        assertThat(roundTrip(42L)).isEqualTo(42L);
        assertThat(roundTrip(3600)).isEqualTo(3600);
        assertThat(roundTrip("Course created.")).isEqualTo("Course created.");
    }

    @Test
    void sessionClassesDeclareASerialVersionUid() throws Exception {
        assertThat(PendingCheckIn.class.getDeclaredField("serialVersionUID")).isNotNull();
        assertThat(ObjectStreamClass.lookup(PendingCheckIn.class).getSerialVersionUID()).isEqualTo(1L);
    }

    @Test
    void theSignedInOidcUserAndItsSecurityContextRoundTrip() throws Exception {
        Instant now = Instant.parse("2026-10-16T06:00:05Z");
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .claim("iss", URI.create("https://accounts.google.com").toURL()) // decoded ID tokens carry a URL
                .subject("sub-ana")
                .audience(List.of("client-id"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("email", "ana@student.example.ro")
                .claim("email_verified", true)
                .claim("given_name", "Ana")
                .build();
        Map<String, Object> userInfoClaims = new LinkedHashMap<>();
        userInfoClaims.put("sub", "sub-ana");
        userInfoClaims.put("name", "Ana Popescu");
        userInfoClaims.put("hd", "university.example.ro");
        OidcUserInfo userInfo = new OidcUserInfo(userInfoClaims);
        DefaultOidcUser user = new DefaultOidcUser(Set.of(new OidcUserAuthority(idToken, userInfo),
                new SimpleGrantedAuthority("ROLE_STUDENT"), new SimpleGrantedAuthority("ROLE_TEACHER")),
                idToken, userInfo, "sub");
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
        authentication.setDetails(new WebAuthenticationDetails("127.0.0.1", null));
        SecurityContextImpl context = new SecurityContextImpl(authentication);

        Object restored = roundTrip(context);

        assertThat(restored).isInstanceOf(SecurityContextImpl.class).usingRecursiveComparison().isEqualTo(context);
        DefaultOidcUser restoredUser = (DefaultOidcUser) ((SecurityContextImpl) restored).getAuthentication()
                .getPrincipal();
        assertThat(restoredUser.getSubject()).isEqualTo("sub-ana");
        assertThat(restoredUser.getEmailVerified()).isTrue();
    }

    @Test
    void oauth2ClientStateRoundTrips() {
        ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("client-id").clientSecret("client-secret").build();
        Instant now = Instant.now();
        Map<String, OAuth2AuthorizedClient> authorizedClients = new HashMap<>();
        authorizedClients.put("google", new OAuth2AuthorizedClient(google, "sub-ana",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(3600),
                        Set.of("openid", "email", "profile"))));
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(google.getProviderDetails().getAuthorizationUri())
                .clientId("client-id")
                .redirectUri("http://localhost/login/oauth2/code/google")
                .scopes(Set.of("openid", "profile", "email"))
                .state("state-123")
                .attributes(a -> {
                    a.put("registration_id", "google");
                    a.put("nonce", "raw-nonce");
                })
                .additionalParameters(Map.of("nonce", "hashed-nonce"))
                .build();

        assertThat(roundTrip(authorizedClients)).usingRecursiveComparison().isEqualTo(authorizedClients);
        assertThat(roundTrip(authorizationRequest)).usingRecursiveComparison().isEqualTo(authorizationRequest);
    }

    @Test
    void csrfTokenFlashMapsAndSavedRequestRoundTrip() {
        DefaultCsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-value");
        assertThat(roundTrip(csrf)).usingRecursiveComparison().isEqualTo(csrf);

        FlashMap flash = new FlashMap();
        flash.put("justCheckedIn", true);
        flash.put("createdCourseId", 42L);
        flash.put("message", "Course created.");
        flash.setTargetRequestPath("/a/x/done");
        flash.addTargetRequestParam("t", "token");
        flash.startExpirationPeriod(180);
        List<FlashMap> flashMaps = new ArrayList<>(List.of(flash));
        assertThat(roundTrip(flashMaps)).usingRecursiveComparison().isEqualTo(flashMaps);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/teacher/courses");
        request.setQueryString("x=1");
        request.addParameter("x", "1");
        request.addHeader("Accept-Language", "ro-RO");
        request.addPreferredLocale(java.util.Locale.forLanguageTag("ro-RO"));
        request.setCookies(new jakarta.servlet.http.Cookie("device_id", "abc.def"));
        new HttpSessionRequestCache().saveRequest(request, new MockHttpServletResponse());
        Object savedRequest = request.getSession().getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        assertThat(savedRequest).isNotNull();
        assertThat(roundTrip(savedRequest)).usingRecursiveComparison().isEqualTo(savedRequest);
    }

    @Test
    void arraysOfAllowedTypesRoundTrip() {
        assertThat((byte[]) roundTrip(new byte[] {1, 2, 3})).containsExactly(1, 2, 3);
        assertThat((String[]) roundTrip(new String[] {"a", "b"})).containsExactly("a", "b");
        assertThat((Object[]) roundTrip(new Object[] {1L, "x", new int[] {7}})).hasSize(3);
    }

    // ------------------------------------------------------------------ never throws on read

    @Test
    void nullAndEmptyAreNull() {
        assertThat(serializer.serialize(null)).isEmpty();
        assertThat(serializer.deserialize(null)).isNull();
        assertThat(serializer.deserialize(new byte[0])).isNull();
    }

    @Test
    void corruptOrTruncatedBytesAreUnreadable() {
        assertThat(serializer.deserialize("not a java object".getBytes(StandardCharsets.UTF_8)))
                .isSameAs(SessionRedisSerializer.UNREADABLE);
        byte[] valid = serializer.serialize(new PendingCheckIn(UUID.randomUUID(), Instant.now(), "h"));
        assertThat(serializer.deserialize(Arrays.copyOf(valid, valid.length / 2)))
                .isSameAs(SessionRedisSerializer.UNREADABLE);
    }

    @Test
    void aClassThatNoLongerExistsIsUnreadable() {
        // Simulates a deploy that removed/renamed a class: same-length name, so the stream stays well-formed.
        byte[] bytes = serializer.serialize(new RenamedLater("x"));
        byte[] renamed = replace(bytes, "RenamedLater", "RemovedLater");
        assertThat(serializer.deserialize(renamed)).isSameAs(SessionRedisSerializer.UNREADABLE);
    }

    @Test
    void aChangedSerialVersionUidIsUnreadable() {
        byte[] bytes = serializer.serialize(new Versioned("x"));
        // Versioned declares serialVersionUID = 0x0102030405060708; flip it as a newer class version would.
        byte[] changed = replace(bytes, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, new byte[] {8, 7, 6, 5, 4, 3, 2, 1});
        assertThat(serializer.deserialize(changed)).isSameAs(SessionRedisSerializer.UNREADABLE);
    }

    @Test
    void classesOutsideTheAllowlistAreRejectedEvenWhenNested() {
        assertThat(serializer.deserialize(serializer.serialize(new BadAttributeValueExpException("x"))))
                .isSameAs(SessionRedisSerializer.UNREADABLE);
        Map<String, Object> wrapper = new HashMap<>();
        wrapper.put("inner", new BadAttributeValueExpException("x"));
        assertThat(serializer.deserialize(serializer.serialize(wrapper))).isSameAs(SessionRedisSerializer.UNREADABLE);
    }

    @Test
    void nonSerializableValuesFailLoudlyOnWrite() {
        assertThatThrownBy(() -> serializer.serialize(new Object())).isInstanceOf(SerializationException.class);
    }

    // ------------------------------------------------------------------ helpers

    record RenamedLater(String value) implements Serializable {}

    static final class Versioned implements Serializable {
        private static final long serialVersionUID = 0x0102030405060708L;
        @SuppressWarnings("unused")
        private final String value;

        Versioned(String value) {
            this.value = value;
        }
    }

    private static byte[] replace(byte[] bytes, String from, String to) {
        return replace(bytes, from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] replace(byte[] bytes, byte[] from, byte[] to) {
        byte[] copy = bytes.clone();
        outer:
        for (int i = 0; i <= copy.length - from.length; i++) {
            for (int j = 0; j < from.length; j++) {
                if (copy[i + j] != from[j]) {
                    continue outer;
                }
            }
            System.arraycopy(to, 0, copy, i, to.length);
            return copy;
        }
        throw new AssertionError("pattern not found in serialized bytes");
    }
}
