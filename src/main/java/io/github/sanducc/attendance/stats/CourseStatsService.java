package io.github.sanducc.attendance.stats;

import io.github.sanducc.attendance.common.RomanianCollator;
import io.github.sanducc.attendance.course.Course;
import io.github.sanducc.attendance.course.CourseService;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-student attendance stats of a course (#8), for the stats page and its exports. Access goes only through
 * {@link CourseService#getOwnedCourse}: someone else's course is "not found", like an unknown one. Two aggregate
 * queries per call, whatever the number of sessions and students. Logs carry ids and counts only.
 */
@Service
public class CourseStatsService {

    private static final Logger log = LoggerFactory.getLogger(CourseStatsService.class);

    private final CourseService courses;
    private final CourseStatsRepository stats;

    public CourseStatsService(CourseService courses, CourseStatsRepository stats) {
        this.courses = courses;
        this.stats = stats;
    }

    /** See {@link CourseStats} for the counting rules. */
    @Transactional(readOnly = true)
    public CourseStats courseStats(long teacherId, long courseId) {
        Course course = courses.getOwnedCourse(teacherId, courseId);
        SessionTotals totals = stats.sessionTotals(course.getId());
        long total = totals.sessions() == null ? 0 : totals.sessions();
        long withEntries = totals.sessionsWithEntries() == null ? 0 : totals.sessionsWithEntries();
        List<AttendeeStats> attendees = withEntries == 0 ? List.of()
                : stats.attendees(course.getId()).stream().sorted(order()).toList();
        log.info("Teacher {} read the attendance stats of course {}: {} session(s), {} attendee(s)",
                teacherId, course.getId(), total, attendees.size());
        return new CourseStats(course, total, total - withEntries, totals.firstOpenedAt(), totals.lastOpenedAt(),
                attendees);
    }

    /** Last name, first name (Romanian alphabet); same names: students before typed names, then by student id. */
    private static Comparator<AttendeeStats> order() {
        Collator collator = RomanianCollator.create();
        return Comparator.comparing((AttendeeStats a) -> nullToEmpty(a.lastName()), collator)
                .thenComparing(a -> nullToEmpty(a.firstName()), collator)
                .thenComparing(AttendeeStats::studentId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
