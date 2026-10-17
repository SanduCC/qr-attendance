package io.github.sanducc.attendance.course;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Anti-cheat rule 7: the Google Workspace domains a course accepts, compared with the {@code hd} ("hosted domain")
 * claim of the student's Google ID token. Google sets {@code hd} only for Workspace accounts, so a consumer Gmail
 * account never matches, and an email address alone (which anyone can put in a display name, or which an alias can
 * fake) is never used.
 *
 * <p>Stored in {@code course.allowed_domains} as a comma-separated, lowercase list; null or empty = no restriction.
 */
public final class WorkspaceDomains {

    public static final int MAX_DOMAINS = 5;
    /** Column width in V5. */
    public static final int MAX_STORED_LENGTH = 500;

    /** Plain ASCII host names with at least two labels (IDN domains in their punycode "xn--" form). */
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{3,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$");
    /** Consumer Google accounts: never carry an {@code hd} claim, so a lock to them would reject everyone. */
    private static final Set<String> CONSUMER_DOMAINS = Set.of("gmail.com", "googlemail.com");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,;]+");

    private WorkspaceDomains() {
    }

    /** A teacher-facing validation problem; the message is shown next to the field. */
    public static final class InvalidDomainsException extends IllegalArgumentException {
        public InvalidDomainsException(String message) {
            super(message);
        }
    }

    /**
     * Parses what a teacher typed, e.g. {@code "university.ro, @Student.University.ro"}: separators are commas,
     * semicolons or spaces, a leading {@code @} is dropped, case is ignored, duplicates are removed.
     *
     * @return the normalized domains in input order; empty for null or blank input
     * @throws InvalidDomainsException when a domain is malformed, a consumer Gmail domain, or there are too many
     */
    public static List<String> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        return normalize(Arrays.asList(SEPARATORS.split(input.strip())));
    }

    /** Validates and normalizes an already split list (the service re-checks whatever the controller passes). */
    public static List<String> normalize(Collection<String> domains) {
        if (domains == null) {
            return List.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String raw : domains) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String domain = raw.strip().toLowerCase(Locale.ROOT);
            if (domain.startsWith("@")) {
                domain = domain.substring(1);
            }
            if (domain.endsWith(".")) {
                domain = domain.substring(0, domain.length() - 1);
            }
            if (!DOMAIN.matcher(domain).matches()) {
                throw new InvalidDomainsException("\"" + raw.strip() + "\" isn't a domain like university.ro");
            }
            if (CONSUMER_DOMAINS.contains(domain)) {
                throw new InvalidDomainsException("Personal Gmail accounts have no Workspace domain: enter your "
                        + "school's own domain, e.g. university.ro");
            }
            result.add(domain);
        }
        if (result.size() > MAX_DOMAINS) {
            throw new InvalidDomainsException("At most " + MAX_DOMAINS + " domains");
        }
        List<String> list = List.copyOf(result);
        if (toColumn(list) != null && toColumn(list).length() > MAX_STORED_LENGTH) {
            throw new InvalidDomainsException("At most " + MAX_STORED_LENGTH + " characters");
        }
        return list;
    }

    /** True when there is no restriction, or the ID token's {@code hd} claim is one of the domains (any case). */
    public static boolean allows(List<String> allowed, String hostedDomain) {
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        if (hostedDomain == null || hostedDomain.isBlank()) {
            return false;
        }
        return allowed.contains(hostedDomain.strip().toLowerCase(Locale.ROOT));
    }

    /** "@university.ro" or "@a.ro or @b.ro", for messages. */
    public static String describe(List<String> allowed) {
        List<String> at = new ArrayList<>(allowed.size());
        for (String d : allowed) {
            at.add("@" + d);
        }
        if (at.size() <= 1) {
            return String.join("", at);
        }
        return String.join(", ", at.subList(0, at.size() - 1)) + " or " + at.getLast();
    }

    /** For the edit form. */
    public static String format(List<String> allowed) {
        return String.join(", ", allowed);
    }

    static String toColumn(List<String> domains) {
        return domains == null || domains.isEmpty() ? null : String.join(",", domains);
    }

    static List<String> fromColumn(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        return Arrays.stream(column.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }
}
