package io.github.sanducc.attendance.checkin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByProviderAndProviderSubject(Provider provider, String providerSubject);

    /**
     * The students of a course: everyone with an entry in any of its sessions, minus those already in
     * {@code excludeSessionId}. The teacher picks from these when adding a manual entry.
     */
    @Query("""
            select s from Student s
            where exists (select 1 from AttendanceRecord r, AttendanceSession x
                          where r.studentId = s.id and r.sessionId = x.id and x.courseId = :courseId)
              and not exists (select 1 from AttendanceRecord r2
                              where r2.studentId = s.id and r2.sessionId = :excludeSessionId)
            order by lower(s.lastName), lower(s.firstName), s.id
            """)
    List<Student> findCourseStudentsNotIn(@Param("courseId") long courseId,
                                          @Param("excludeSessionId") UUID excludeSessionId);

    /** Entries of the student in the course's sessions; more than zero makes them one of the course's students. */
    @Query("""
            select count(r) from AttendanceRecord r, AttendanceSession x
            where r.studentId = :studentId and r.sessionId = x.id and x.courseId = :courseId
            """)
    long countEntriesInCourse(@Param("studentId") long studentId, @Param("courseId") long courseId);

    /** Retention: once their records are purged, students' personal data goes too. */
    @Modifying
    @Query(value = """
            delete from student s
            where not exists (select 1 from attendance_record r where r.student_id = s.id)
            """, nativeQuery = true)
    int deleteWithoutRecords();
}
