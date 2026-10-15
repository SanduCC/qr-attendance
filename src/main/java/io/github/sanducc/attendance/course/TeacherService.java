package io.github.sanducc.attendance.course;

import io.github.sanducc.attendance.common.AuthenticatedUser;
import io.github.sanducc.attendance.config.AttendanceProperties;
import java.time.Clock;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Teachers come from an allowlist: the {@code attendance.teacher-emails} config or a row in the teacher table. */
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

    @Transactional(readOnly = true)
    public boolean isAllowlisted(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalized = normalize(email);
        return properties.teacherEmails().contains(normalized) || teachers.existsByEmail(normalized);
    }

    @Transactional
    public Teacher registerLogin(String email, String name) {
        String normalized = normalize(email);
        String displayName = name == null || name.isBlank() ? normalized : name.trim();
        return teachers.findByEmail(normalized)
                .map(existing -> {
                    existing.setName(displayName);
                    return existing;
                })
                .orElseGet(() -> teachers.save(new Teacher(normalized, displayName, clock.instant())));
    }

    /** Resolves the signed-in teacher; every teacher-facing service call is scoped by this id. */
    @Transactional(readOnly = true)
    public long requireTeacherId(OAuth2AuthenticationToken authentication) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        if (!user.emailVerified() || user.email() == null) {
            throw new AccessDeniedException("Teacher email not verified");
        }
        return teachers.findByEmail(normalize(user.email()))
                .map(Teacher::getId)
                .orElseThrow(() -> new AccessDeniedException("Not a teacher"));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
