package io.github.sanducc.attendance.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/** Raw request facts the check-in rules need. Hashed by the service before anything is stored. */
public record ClientContext(String deviceId, String ip, String userAgent) {

    public static ClientContext from(HttpServletRequest request) {
        String deviceId = DeviceIdFilter.deviceId(request);
        if (deviceId == null) {
            throw new IllegalStateException("DeviceIdFilter did not run for " + request.getRequestURI());
        }
        return new ClientContext(deviceId, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
