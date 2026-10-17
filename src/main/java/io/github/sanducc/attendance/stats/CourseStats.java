package io.github.sanducc.attendance.stats;

import io.github.sanducc.attendance.course.Course;
import java.time.Instant;
import java.util.List;

/**
 * Per-student attendance of one course (#8).
 *
 * <p>Counting rules: {@code totalSessions} is every session of the course that still exists, open and closed alike,
 * including sessions nobody checked in to ({@code sessionsWithoutEntries} says how many); sessions removed by the
 * retention purge are gone from both sides. A person attended a session when they have an entry in it, scanned or
 * added by the teacher. Manual entries for a student count for that student; manual entries without an account are
 * grouped by their name (case-insensitive) and marked as typed names.
 *
 * @param firstSessionAt null when the course has no sessions, like {@code lastSessionAt}
 * @param attendees      sorted by last name, then first name (Romanian alphabet)
 */
public record CourseStats(Course course, long totalSessions, long sessionsWithoutEntries, Instant firstSessionAt,
                          Instant lastSessionAt, List<AttendeeStats> attendees) {

    public CourseStats {
        attendees = List.copyOf(attendees);
    }

    /** People listed under a typed name (manual entries without an account). */
    public long typedNameCount() {
        return attendees.stream().filter(AttendeeStats::typedName).count();
    }

    /** {@code attended} as a whole percentage of {@code total}, rounded down; 0 when there are no sessions. */
    public static int percent(long attended, long total) {
        return total <= 0 ? 0 : (int) (attended * 100 / total);
    }
}
