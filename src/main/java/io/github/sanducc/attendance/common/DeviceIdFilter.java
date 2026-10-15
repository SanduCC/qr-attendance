package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.config.AttendanceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every student browser a long-lived, signed, httpOnly {@code device_id} cookie so one
 * phone can't check in several accounts in the same session. Only the hash is ever stored.
 */
@Component
public class DeviceIdFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "device_id";
    public static final String REQUEST_ATTRIBUTE = DeviceIdFilter.class.getName() + ".deviceId";

    /** Browsers cap cookie lifetime at 400 days. */
    private static final Duration MAX_AGE = Duration.ofDays(400);
    private static final String SIGN_PURPOSE = "device-cookie";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

    private final SecureRandom random = new SecureRandom();
    private final PepperedHasher hasher;
    private final boolean secureCookie;

    public DeviceIdFilter(PepperedHasher hasher, AttendanceProperties properties) {
        this.hasher = hasher;
        this.secureCookie = properties.security().secureCookies();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/a/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String deviceId = readValidCookie(request);
        if (deviceId == null) {
            deviceId = newDeviceId();
            ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, encode(deviceId))
                    .httpOnly(true)
                    .secure(secureCookie)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(MAX_AGE)
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        request.setAttribute(REQUEST_ATTRIBUTE, deviceId);
        chain.doFilter(request, response);
    }

    public static String deviceId(HttpServletRequest request) {
        return (String) request.getAttribute(REQUEST_ATTRIBUTE);
    }

    String encode(String deviceId) {
        return deviceId + "." + B64.encodeToString(hasher.sign(SIGN_PURPOSE, deviceId));
    }

    /** Returns the device id if the cookie value carries a valid signature, else null. */
    String decode(String cookieValue) {
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || cookieValue.length() > 128) {
            return null;
        }
        String deviceId = cookieValue.substring(0, dot);
        byte[] signature;
        try {
            signature = B64_DECODER.decode(cookieValue.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return MessageDigest.isEqual(signature, hasher.sign(SIGN_PURPOSE, deviceId)) ? deviceId : null;
    }

    private String readValidCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && cookie.getValue() != null) {
                String deviceId = decode(cookie.getValue());
                if (deviceId != null) {
                    return deviceId;
                }
            }
        }
        return null;
    }

    private String newDeviceId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
