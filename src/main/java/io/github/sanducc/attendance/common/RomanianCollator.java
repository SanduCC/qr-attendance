package io.github.sanducc.attendance.common;

import java.text.Collator;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.util.Locale;

/**
 * Sorting of Romanian names, shared by the exports and the per-student stats.
 *
 * <p>The JDK's built-in "ro" rules predate the comma-below letters (Ș U+0218, Ț U+021A), so they would sort
 * "Șerban" between "Sârbu" and "Stan", and they put Â before Ă. This tailoring follows the Romanian alphabet
 * (A Ă Â B … I Î … S Ș T Ț …), with the legacy cedilla forms (Ş, Ţ) treated as variants of Ș, Ț.
 */
public final class RomanianCollator {

    private static final String ROMANIAN_TAILORING = "& A < ă, Ă < â, Â"
            + " & I < î, Î"
            + " & S < ș, Ș ; ş, Ş"
            + " & T < ț, Ț ; ţ, Ţ";
    private static final String ROMANIAN_RULES = ((RuleBasedCollator) Collator.getInstance(Locale.forLanguageTag("ro")))
            .getRules() + ROMANIAN_TAILORING;

    private RomanianCollator() {
    }

    /** A new instance per call because {@link Collator} is not thread-safe. */
    public static Collator create() {
        try {
            RuleBasedCollator collator = new RuleBasedCollator(ROMANIAN_RULES);
            collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
            return collator;
        } catch (ParseException e) {
            throw new IllegalStateException("Invalid Romanian collation rules", e);
        }
    }
}
