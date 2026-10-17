package io.github.sanducc.attendance.stats;

import java.time.Instant;

/**
 * One person's attendance in a course (JPQL constructor result, hence the boxed numbers). Either a student
 * ({@code studentId} set: real scans plus manual entries the teacher added for them) or, with {@code studentId} and
 * {@code email} null, everyone typed in by hand under the same name (case-insensitive) without an account.
 *
 * @param attended               the number of the course's sessions this person is in
 * @param lastAttendedSessionAt  when the last of those sessions opened (the session's date, not the entry's time:
 *                               a manual entry can be added days later)
 */
public record AttendeeStats(Long studentId, String firstName, String lastName, String email, Long attended,
                            Instant lastAttendedSessionAt) {

    /** A manual entry without an account (a typed name), not a student. */
    public boolean typedName() {
        return studentId == null;
    }

    /** Attended sessions as a whole percentage of {@code totalSessions}, rounded down (never shows 100% early). */
    public int percentOf(long totalSessions) {
        return CourseStats.percent(attended, totalSessions);
    }
}
