package io.github.sanducc.attendance.config;

import io.github.sanducc.attendance.course.TeacherService;
import io.github.sanducc.attendance.course.TeacherService.LoginRoles;
import java.util.HashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Everyone who signs in is a student. The teacher and admin roles are never chosen by the user: they come from
 * {@link TeacherService#rolesForLogin} (admin-managed teacher list, admins from config).
 */
@Component
public class AppOidcUserService extends OidcUserService {

    public static final String ROLE_STUDENT = "ROLE_STUDENT";
    public static final String ROLE_TEACHER = "ROLE_TEACHER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final TeacherService teacherService;

    public AppOidcUserService(TeacherService teacherService) {
        this.teacherService = teacherService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(userRequest);
        Set<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(ROLE_STUDENT));

        LoginRoles roles = teacherService.rolesForLogin(user.getEmail(),
                Boolean.TRUE.equals(user.getEmailVerified()), user.getFullName());
        if (roles.teacher()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_TEACHER));
        }
        if (roles.admin()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
        }
        return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "sub");
    }
}
