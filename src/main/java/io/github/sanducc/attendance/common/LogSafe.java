package io.github.sanducc.attendance.common;

/**
 * Makes a client-controlled value safe to put in a log line (log injection, CWE-117): line breaks and other control
 * or invisible formatting characters (tabs, ANSI escapes, {@code U+2028}, bidi overrides...) become {@code _}, so a
 * request can't forge extra log lines or hide text, and anything longer than {@link #MAX_LENGTH} characters is cut
 * and marked with {@code ...}.
 *
 * <p>Only for values a log line really needs (a request path, say). Don't log personal data through it: an email
 * address goes into logs only as {@link EmailAddresses#logId}.
 */
public final class LogSafe {

    public static final int MAX_LENGTH = 200;
    static final char REPLACEMENT = '_';
    static final String CUT_MARKER = "...";

    private LogSafe() {
    }

    public static String of(Object value) {
        if (value == null) {
            return "null";
        }
        String text = value.toString();
        boolean cut = text.length() > MAX_LENGTH;
        if (cut) {
            int end = Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
            text = text.substring(0, end);
        }
        StringBuilder safe = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            safe.append(isUnsafe(c) ? REPLACEMENT : c);
        }
        // CR and LF are already replaced above; these explicit calls are what CodeQL's java/log-injection query
        // recognizes as the sanitizer.
        String neutralized = safe.toString().replace('\r', REPLACEMENT).replace('\n', REPLACEMENT);
        return cut ? neutralized + CUT_MARKER : neutralized;
    }

    private static boolean isUnsafe(char c) {
        if (Character.isISOControl(c)) { // C0 (CR, LF, TAB, ESC...), DEL and C1 (NEL...)
            return true;
        }
        int type = Character.getType(c);
        return type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.FORMAT; // zero-width and bidi control characters
    }
}
