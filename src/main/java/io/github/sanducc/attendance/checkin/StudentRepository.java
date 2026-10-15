package io.github.sanducc.attendance.checkin;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface StudentRepository extends JpaRepository<Student, Long> {

    Optional<Student> findByProviderAndProviderSubject(Provider provider, String providerSubject);

    /** Retention: once their records are purged, students' personal data goes too. */
    @Modifying
    @Query(value = """
            delete from student s
            where not exists (select 1 from attendance_record r where r.student_id = s.id)
            """, nativeQuery = true)
    int deleteWithoutRecords();
}
