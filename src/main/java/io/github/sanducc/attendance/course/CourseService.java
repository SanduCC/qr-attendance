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

    @Transactional
    public Course createCourse(long teacherId, String name, String code) {
        return courses.save(new Course(teacherId, name.trim(), code.trim(), clock.instant()));
    }

    /** Throws NotFound both when the course doesn't exist and when another teacher owns it. */
    @Transactional(readOnly = true)
    public Course getOwnedCourse(long teacherId, long courseId) {
        return courses.findByIdAndTeacherId(courseId, teacherId)
                .orElseThrow(() -> new NotFoundException("Course not found"));
    }
}
