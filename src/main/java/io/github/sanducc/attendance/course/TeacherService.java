package io.github.sanducc.attendance.course;

import io.github.sanducc.attendance.common.AuthenticatedUser;
import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.config.AttendanceProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may teach. Admins come only from {@code attendance.admin-emails} (config, not editable in the app) and are
 * teachers too. Everyone else is a teacher only if an admin added them and hasn't deactivated them. Nobody can
 * make themselves a teacher by signing in.
 */
@Service
public class TeacherService {

    private final TeacherRepository teachers;
    private final AttendanceProperties properties;
    private final Clock clock;

    public TeacherService(TeacherRepository teachers, AttendanceProperties properties, Clock clock) {
        this.teachers = teachers;
        this.properties = properties;
        this.clock = clock;
    }

    /** Roles granted at sign-in, on top of the student role everyone gets. */
    public record LoginRoles(boolean teacher, boolean admin) {}

    public record TeacherRow(Long id, String email, String name, boolean active, boolean admin, String addedBy,
                             Instant lastLoginAt) {}

    public boolean isAdmin(String email) {
        return email != null && properties.adminEmails().contains(normalize(email));
    }

    /** Called on every sign-in. Only a verified email can carry the teacher or admin role. */
    @Transactional
    public LoginRoles rolesForLogin(String email, boolean emailVerified, String name) {
        if (!emailVerified || email == null || email.isBlank()) {
            return new LoginRoles(false, false);
        }
        String normalized = normalize(email);
        Instant now = clock.instant();
        if (isAdmin(normalized)) {
            Teacher admin = teachers.findByEmail(normalized)
                    .orElseGet(() -> new Teacher(normalized, null, now));
            admin.activate();
            admin.recordLogin(name, now);
            teachers.save(admin);
            return new LoginRoles(true, true);
        }
        return teachers.findByEmail(normalized)
                .filter(Teacher::isActive)
                .map(teacher -> {
                    teacher.recordLogin(name, now);
                    return new LoginRoles(true, false);
                })
                .orElse(new LoginRoles(false, false));
    }

    /**
     * Resolves the signed-in teacher; every teacher-facing service call is scoped by this id. Re-checks the DB on
     * each request, so deactivating a teacher takes effect immediately, not at their next sign-in.
     */
    @Transactional(readOnly = true)
    public long requireTeacherId(OAuth2AuthenticationToken authentication) {
        String email = requireVerifiedEmail(authentication);
        return teachers.findByEmail(email)
                .filter(teacher -> teacher.isActive() || isAdmin(email))
                .map(Teacher::getId)
                .orElseThrow(() -> new AccessDeniedException("Not a teacher"));
    }

    /** Returns the signed-in admin's normalized email, or throws. */
    public String requireAdminEmail(OAuth2AuthenticationToken authentication) {
        String email = requireVerifiedEmail(authentication);
        requireAdmin(email);
        return email;
    }

    @Transactional(readOnly = true)
    public List<TeacherRow> listTeachers(String actingAdminEmail) {
        requireAdmin(actingAdminEmail);
        return teachers.findAll().stream()
                .sorted(Comparator.comparing(Teacher::isActive).reversed().thenComparing(Teacher::getEmail))
                .map(t -> new TeacherRow(t.getId(), t.getEmail(), t.getName(), t.isActive(), isAdmin(t.getEmail()),
                        t.getAddedBy(), t.getLastLoginAt()))
                .toList();
    }

    /** Adds a teacher by email, or reactivates one who was deactivated. */
    @Transactional
    public Teacher addTeacher(String actingAdminEmail, String email) {
        requireAdmin(actingAdminEmail);
        String normalized = normalize(email);
        return teachers.findByEmail(normalized)
                .map(existing -> {
                    existing.activate();
                    return existing;
                })
                .orElseGet(() -> teachers.save(Teacher.invited(normalized, normalize(actingAdminEmail),
                        clock.instant())));
    }

    @Transactional
    public Teacher setActive(String actingAdminEmail, long teacherId, boolean active) {
        requireAdmin(actingAdminEmail);
        Teacher teacher = teachers.findById(teacherId).orElseThrow(() -> new NotFoundException("Teacher not found"));
        if (!active && isAdmin(teacher.getEmail())) {
            throw new IllegalArgumentException("Admins can't be deactivated here; they come from ADMIN_EMAILS.");
        }
        if (active) {
            teacher.activate();
        } else {
            teacher.deactivate();
        }
        return teacher;
    }

    private void requireAdmin(String email) {
        if (!isAdmin(email)) {
            throw new AccessDeniedException("Not an admin");
        }
    }

    private static String requireVerifiedEmail(OAuth2AuthenticationToken authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        if (!user.emailVerified() || user.email() == null) {
            throw new AccessDeniedException("Email not verified");
        }
        return normalize(user.email());
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
