package io.github.sanducc.attendance.export;

import io.github.sanducc.attendance.stats.AttendeeStats;
import io.github.sanducc.attendance.stats.CourseStats;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-student stats export of a course (#8), for both formats: the same title block as a session export, then one
 * row per person in the order of {@link CourseStats#attendees()} (Romanian alphabet), numbered 1..n. Dates are already
 * converted to the display zone.
 */
public record StatsSheet(String title, String subtitle, String filenameBase, List<Row> rows) {

    public static final List<String> HEADERS = List.of("Nr.", "Last name", "First name", "Email", "Attended",
            "Sessions", "Percentage", "Last attended", "Source");

    /** The "Source" column: a student with an account, or a name the teacher typed in (manual entries only). */
    public static final String SOURCE_ACCOUNT = "account";
    public static final String SOURCE_MANUAL = AttendanceSheet.SOURCE_MANUAL;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    public StatsSheet {
        rows = List.copyOf(rows);
    }

    /**
     * One data row; {@code email} is null for a typed name, {@code percent} is the whole percentage (rounded down),
     * {@code lastAttended} the date of the last session attended, in the display zone.
     */
    public record Row(int nr, String lastName, String firstName, String email, long attended, long sessions,
                      int percent, LocalDate lastAttended, boolean typedName) {

        /** The "Source" cell. */
        public String source() {
            return typedName ? SOURCE_MANUAL : SOURCE_ACCOUNT;
        }
    }

    public static StatsSheet of(CourseStats stats, ZoneId zone, LocalDate today) {
        String title = AttendanceSheet.courseTitle(stats.course().getName(), stats.course().getCode());
        long total = stats.totalSessions();
        long typed = stats.typedNameCount();
        String subtitle = "Attendance per student on " + DATE.format(today) + " · " + total
                + (total == 1 ? " session" : " sessions")
                + (stats.sessionsWithoutEntries() == 0 ? "" : " (" + stats.sessionsWithoutEntries()
                        + " without check-ins)")
                + span(stats.firstSessionAt(), stats.lastSessionAt(), zone)
                + " · " + stats.attendees().size() + (stats.attendees().size() == 1 ? " student" : " students")
                + (typed == 0 ? "" : " (" + typed + " manual)");
        String filenameBase = AttendanceSheet.filenameBase(stats.course().getCode(), stats.course().getName())
                + "_stats_" + DATE.format(today);

        List<Row> rows = new ArrayList<>(stats.attendees().size());
        for (AttendeeStats a : stats.attendees()) {
            rows.add(new Row(rows.size() + 1, a.lastName(), a.firstName(), a.email(), a.attended(), total,
                    a.percentOf(total), a.lastAttendedSessionAt().atZone(zone).toLocalDate(), a.typedName()));
        }
        return new StatsSheet(title, subtitle, filenameBase, rows);
    }

    public String filename(String extension) {
        return filenameBase + "." + extension;
    }

    private static String span(Instant first, Instant last, ZoneId zone) {
        if (first == null || last == null) {
            return "";
        }
        LocalDate from = first.atZone(zone).toLocalDate();
        LocalDate to = last.atZone(zone).toLocalDate();
        return ", " + DATE.format(from) + (from.equals(to) ? "" : "–" + DATE.format(to));
    }
}
