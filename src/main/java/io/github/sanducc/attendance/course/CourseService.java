package io.github.sanducc.attendance.course;

import io.github.sanducc.attendance.common.NotFoundException;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseService {

    private final CourseRepository courses;
    private final Clock clock;

    public CourseService(CourseRepository courses, Clock clock) {
        this.courses = courses;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Course> listCourses(long teacherId) {
        return courses.findByTeacherIdOrderByNameAsc(teacherId);
    }

    /** The code is optional: null or blank is stored as null. No domain lock. */
    @Transactional
    public Course createCourse(long teacherId, String name, String code) {
        return createCourse(teacherId, name, code, List.of());
    }

    /**
     * Creates a course; {@code allowedDomains} is its optional Workspace domain lock (rule 7, empty = none).
     *
     * @throws WorkspaceDomains.InvalidDomainsException when a domain is invalid
     */
    @Transactional
    public Course createCourse(long teacherId, String name, String code, List<String> allowedDomains) {
        List<String> domains = WorkspaceDomains.normalize(allowedDomains);
        Course course = new Course(teacherId, name.trim(), trimCode(code), clock.instant());
        course.setAllowedDomains(domains);
        return courses.save(course);
    }

    /**
     * Edits the teacher's own course: name, optional code and the Workspace domain lock. Throws NotFound for someone
     * else's course, so only the course's teacher can set or lift the lock. Takes effect from the next check-in,
     * including in sessions that are already open.
     *
     * @throws WorkspaceDomains.InvalidDomainsException when a domain is invalid
     */
    @Transactional
    public Course updateCourse(long teacherId, long courseId, String name, String code,
                               List<String> allowedDomains) {
        List<String> domains = WorkspaceDomains.normalize(allowedDomains);
        Course course = getOwnedCourse(teacherId, courseId);
        course.update(name.trim(), trimCode(code), domains);
        return courses.save(course);
    }

    /** Throws NotFound both when the course doesn't exist and when another teacher owns it. */
    @Transactional(readOnly = true)
    public Course getOwnedCourse(long teacherId, long courseId) {
        return courses.findByIdAndTeacherId(courseId, teacherId)
                .orElseThrow(() -> new NotFoundException("Course not found"));
    }

    private static String trimCode(String code) {
        return code == null || code.isBlank() ? null : code.trim();
    }
}
