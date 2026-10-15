package io.github.sanducc.attendance.course;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findByTeacherIdOrderByNameAsc(Long teacherId);

    Optional<Course> findByIdAndTeacherId(Long id, Long teacherId);
}
