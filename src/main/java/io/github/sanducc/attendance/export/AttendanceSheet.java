package io.github.sanducc.attendance.export;

import io.github.sanducc.attendance.checkin.RecordView;
import io.github.sanducc.attendance.common.RomanianCollator;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import java.text.Collator;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What both export formats show, built in one place: a title block and the check-ins sorted by
 * last name, then first name (Romanian alphabet), then check-in time, numbered 1..n after sorting.
 * Times are already converted to the display zone.
 */
public record AttendanceSheet(String title, String subtitle, String filenameBase, List<Row> rows) {

    public static final List<String> HEADERS = List.of("Nr.", "Last name", "First name", "Email", "Check-in time",
            "Source");

    /** The "Source" column: scanned the QR code, or added by the teacher. */
    public static final String SOURCE_SCAN = "scan";
    public static final String SOURCE_MANUAL = "manual";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SLUG_LENGTH = 40;

    public AttendanceSheet {
        rows = List.copyOf(rows);
    }

    /**
     * One data row; {@code email} may be null, {@code checkedInAt} is local time in the display zone, {@code manual}
     * marks an entry the teacher added by hand.
     */
    public record Row(int nr, String lastName, String firstName, String email, LocalDateTime checkedInAt,
                      boolean manual) {

        /** The "Source" cell. */
        public String source() {
            return manual ? SOURCE_MANUAL : SOURCE_SCAN;
        }
    }

    public static AttendanceSheet of(OwnedSession owned, List<RecordView> records, ZoneId zone) {
        return of(owned.course().getName(), owned.course().getCode(), owned.session().getOpenedAt(),
                owned.session().getClosesAt(), owned.session().getExpectedHeadcount(), records, zone);
    }

    public static AttendanceSheet of(String courseName, String courseCode, Instant openedAt, Instant closesAt,
                                     Integer expectedHeadcount, List<RecordView> records, ZoneId zone) {
        String title = courseTitle(courseName, courseCode);

        ZonedDateTime opened = openedAt.atZone(zone);
        long manual = records.stream().filter(RecordView::manual).count();
        String subtitle = window(opened, closesAt == null ? null : closesAt.atZone(zone))
                + " · " + records.size() + " checked in"
                + (manual == 0 ? "" : " (" + manual + " manual)")
                + (expectedHeadcount == null ? "" : " / expected " + expectedHeadcount);

        String filenameBase = filenameBase(courseCode, courseName) + "_" + DATE.format(opened);

        Collator collator = romanianCollator();
        Comparator<RecordView> order = Comparator
                .comparing((RecordView r) -> nullToEmpty(r.lastName()), collator)
                .thenComparing(r -> nullToEmpty(r.firstName()), collator)
                .thenComparing(RecordView::checkedInAt);
        List<RecordView> sorted = records.stream().sorted(order).toList();
        List<Row> rows = new ArrayList<>(sorted.size());
        for (RecordView r : sorted) {
            rows.add(new Row(rows.size() + 1, r.lastName(), r.firstName(), r.email(),
                    LocalDateTime.ofInstant(r.checkedInAt(), zone), r.manual()));
        }
        return new AttendanceSheet(title, subtitle, filenameBase, rows);
    }

    public String filename(String extension) {
        return filenameBase + "." + extension;
    }

    /** Collation for Romanian names; a new instance per call because {@link Collator} is not thread-safe. */
    static Collator romanianCollator() {
        return RomanianCollator.create();
    }

    /** "Name (CODE)", or just the name when the course has no code. */
    static String courseTitle(String courseName, String courseCode) {
        String name = courseName == null ? "" : courseName.strip();
        String code = courseCode == null ? "" : courseCode.strip();
        return code.isEmpty() ? name : name + " (" + code + ")";
    }

    /**
     * The course code sanitised for a filename; if there is no code (it's optional), an ASCII slug of the
     * course name; if that is empty too, "attendance".
     */
    static String filenameBase(String courseCode, String courseName) {
        String code = courseCode == null ? "" : asciiFold(courseCode.strip()).replaceAll("[^A-Za-z0-9_-]", "_");
        if (!code.isEmpty()) {
            return code;
        }
        String slug = courseName == null ? "" : asciiFold(courseName)
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("_+$", "");
        }
        return slug.isEmpty() ? "attendance" : slug;
    }

    private static String asciiFold(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    private static String window(ZonedDateTime opened, ZonedDateTime closes) {
        if (closes == null) {
            return DATE_TIME.format(opened);
        }
        boolean sameDay = opened.toLocalDate().equals(closes.toLocalDate());
        return DATE_TIME.format(opened) + "–" + (sameDay ? TIME : DATE_TIME).format(closes);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
