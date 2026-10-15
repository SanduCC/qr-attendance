package io.github.sanducc.attendance.config;

import io.github.sanducc.attendance.checkin.PendingCheckIn;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * After sign-in, a student who just scanned a QR code goes straight to the confirm screen;
 * otherwise the saved request (or a role-based home page) wins.
 */
@Component
public class LoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws ServletException, IOException {
        PendingCheckIn pending = PendingCheckIn.from(request.getSession(false));
        if (pending != null) {
            clearAuthenticationAttributes(request);
            getRedirectStrategy().sendRedirect(request, response, "/a/" + pending.sessionId() + "/confirm");
            return;
        }
        super.onAuthenticationSuccess(request, response, authentication);
    }

    @Override
    protected String determineTargetUrl(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) {
        boolean teacher = authentication.getAuthorities().stream()
                .anyMatch(a -> AppOidcUserService.ROLE_TEACHER.equals(a.getAuthority()));
        return teacher ? "/teacher/courses" : "/";
    }
}
