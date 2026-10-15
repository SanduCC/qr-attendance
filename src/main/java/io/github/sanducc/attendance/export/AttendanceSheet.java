package io.github.sanducc.attendance.export;

import io.github.sanducc.attendance.checkin.RecordView;
import io.github.sanducc.attendance.session.SessionService.OwnedSession;
import java.text.Collator;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * What both export formats show, built in one place: a title block and the check-ins sorted by
 * last name, then first name (Romanian alphabet), then check-in time, numbered 1..n after sorting.
 * Times are already converted to the display zone.
 */
public record AttendanceSheet(String title, String subtitle, String filenameBase, List<Row> rows) {

    public static final List<String> HEADERS = List.of("Nr.", "Last name", "First name", "Email", "Check-in time");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SLUG_LENGTH = 40;

    /**
     * The JDK's built-in "ro" rules predate the comma-below letters (Ș U+0218, Ț U+021A), so they would sort
     * "Șerban" between "Sârbu" and "Stan", and they put Â before Ă. This tailoring follows the Romanian alphabet
     * (A Ă Â B … I Î … S Ș T Ț …), with the legacy cedilla forms (Ş, Ţ) treated as variants of Ș, Ț.
     */
    private static final String ROMANIAN_TAILORING = "& A < ă, Ă < â, Â"
            + " & I < î, Î"
            + " & S < ș, Ș ; ş, Ş"
            + " & T < ț, Ț ; ţ, Ţ";
    private static final String ROMANIAN_RULES = ((RuleBasedCollator) Collator.getInstance(Locale.forLanguageTag("ro")))
            .getRules() + ROMANIAN_TAILORING;

    public AttendanceSheet {
        rows = List.copyOf(rows);
    }

    /** One data row; {@code email} may be null, {@code checkedInAt} is local time in the display zone. */
    public record Row(int nr, String lastName, String firstName, String email, LocalDateTime checkedInAt) {}

    public static AttendanceSheet of(OwnedSession owned, List<RecordView> records, ZoneId zone) {
        return of(owned.course().getName(), owned.course().getCode(), owned.session().getOpenedAt(),
                owned.session().getClosesAt(), owned.session().getExpectedHeadcount(), records, zone);
    }

    public static AttendanceSheet of(String courseName, String courseCode, Instant openedAt, Instant closesAt,
                                     Integer expectedHeadcount, List<RecordView> records, ZoneId zone) {
        String name = courseName == null ? "" : courseName.strip();
        String code = courseCode == null ? "" : courseCode.strip();
        String title = code.isEmpty() ? name : name + " (" + code + ")";

        ZonedDateTime opened = openedAt.atZone(zone);
        String subtitle = window(opened, closesAt == null ? null : closesAt.atZone(zone))
                + " · " + records.size() + " checked in"
                + (expectedHeadcount == null ? "" : " / expected " + expectedHeadcount);

        String filenameBase = filenameBase(code, name) + "_" + DATE.format(opened);

        Collator collator = romanianCollator();
        Comparator<RecordView> order = Comparator
                .comparing((RecordView r) -> nullToEmpty(r.lastName()), collator)
                .thenComparing(r -> nullToEmpty(r.firstName()), collator)
                .thenComparing(RecordView::checkedInAt);
        List<RecordView> sorted = records.stream().sorted(order).toList();
        List<Row> rows = new ArrayList<>(sorted.size());
        for (RecordView r : sorted) {
            rows.add(new Row(rows.size() + 1, r.lastName(), r.firstName(), r.email(),
                    LocalDateTime.ofInstant(r.checkedInAt(), zone)));
        }
        return new AttendanceSheet(title, subtitle, filenameBase, rows);
    }

    public String filename(String extension) {
        return filenameBase + "." + extension;
    }

    /** Collation for Romanian names; a new instance per call because {@link Collator} is not thread-safe. */
    static Collator romanianCollator() {
        try {
            RuleBasedCollator collator = new RuleBasedCollator(ROMANIAN_RULES);
            collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
            return collator;
        } catch (ParseException e) {
            throw new IllegalStateException("Invalid Romanian collation rules", e);
        }
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
