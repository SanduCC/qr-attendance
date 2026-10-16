package io.github.sanducc.attendance.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client address behind proxies that <b>append</b> to {@code X-Forwarded-For} (issue #39).
 *
 * <p>Each proxy adds the address it received the request from at the right-hand end, after whatever the client sent.
 * So only the right-most entries are written by proxies we trust; everything to their left, and the whole
 * {@code Forwarded} header a client can send, is client-chosen. With {@code trustedProxyHops = n} the client is the
 * {@code n}-th entry from the right:
 * <ul>
 *   <li>{@code 1}: one proxy in front of the app that appends the client's address: Cloud Run's front end on the
 *       {@code run.app} URL, Caddy, nginx with {@code $proxy_add_x_forwarded_for}. The right-most entry.</li>
 *   <li>{@code 2}: two of them, e.g. an external Application Load Balancer, which appends
 *       {@code <client-ip>,<load-balancer-ip>}.</li>
 *   <li>{@code 0}: trust no header; the TCP peer is the client.</li>
 * </ul>
 * With fewer entries than hops, every entry came from a trusted proxy, so the left-most one is used. Without the
 * header, or if the chosen entry isn't an IP address, it's the TCP peer ({@code getRemoteAddr()}). Several
 * {@code X-Forwarded-For} header lines count as one list, in order.
 */
public final class ForwardedClientIp {

    public static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private static final Logger log = LoggerFactory.getLogger(ForwardedClientIp.class);
    private static final Pattern FORWARDED_FOR = Pattern.compile("(?i)(?:^|;)\\s*for=\\s*\"?([^;\"]+)\"?");
    private static final int MAX_ENTRY_LENGTH = 64;

    private ForwardedClientIp() {
    }

    /** The client address of {@code request}, read from the raw (not yet wrapped) request's headers. */
    public static String resolve(HttpServletRequest request, int trustedProxyHops) {
        List<String> lines = headerLines(request, X_FORWARDED_FOR);
        String ip = resolve(lines, request.getRemoteAddr(), trustedProxyHops);
        if (log.isDebugEnabled()) {
            // Never the addresses themselves (personal data): only the shape, and whether Cloud Run's own Forwarded
            // header (same proxy, same hop count) names the same client. That's the post-deploy self-check.
            List<String> forwarded = forwardedForEntries(headerLines(request, "Forwarded"));
            log.debug("Client IP from {} X-Forwarded-For entries on {} line(s), {} trusted hop(s); "
                            + "Forwarded has {} for= entries, its trusted entry {}",
                    entries(lines).size(), lines.size(), trustedProxyHops, forwarded.size(),
                    forwarded.isEmpty() ? "is absent"
                            : Objects.equals(ip, pick(forwarded, trustedProxyHops)) ? "matches" : "differs");
        }
        return ip;
    }

    static String resolve(List<String> headerLines, String remoteAddr, int trustedProxyHops) {
        if (trustedProxyHops <= 0) {
            return remoteAddr;
        }
        String ip = pick(entries(headerLines), trustedProxyHops);
        return ip != null ? ip : remoteAddr;
    }

    /** The {@code hops}-th entry from the right (the left-most if there are fewer), normalized; null if none. */
    private static String pick(List<String> entries, int hops) {
        if (entries.isEmpty()) {
            return null;
        }
        return normalize(entries.get(Math.max(0, entries.size() - hops)));
    }

    private static List<String> entries(List<String> headerLines) {
        List<String> entries = new ArrayList<>();
        for (String line : headerLines) {
            for (String entry : line.split(",")) {
                if (!entry.isBlank()) {
                    entries.add(entry.trim());
                }
            }
        }
        return entries;
    }

    /** RFC 7239 {@code for=} values, in order, across all elements and lines. Only for the debug self-check. */
    private static List<String> forwardedForEntries(List<String> headerLines) {
        List<String> entries = new ArrayList<>();
        for (String line : headerLines) {
            for (String element : line.split(",")) {
                Matcher matcher = FORWARDED_FOR.matcher(element.trim());
                if (matcher.find()) {
                    entries.add(matcher.group(1).trim());
                }
            }
        }
        return entries;
    }

    /**
     * The canonical form of an IP address entry ({@code 203.0.113.7}, {@code 2001:db8:0:0:0:0:0:1}), accepting the
     * {@code "[v6]:port"} and {@code v4:port} forms; null for anything else (host names are never resolved).
     */
    static String normalize(String entry) {
        String value = entry.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty() || value.length() > MAX_ENTRY_LENGTH || value.indexOf('%') >= 0) {
            return null;
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                return null;
            }
            value = value.substring(1, end);
        } else if (value.indexOf(':') > 0 && value.indexOf(':') == value.lastIndexOf(':')) {
            value = value.substring(0, value.indexOf(':'));   // IPv4:port
        }
        if (!value.chars().allMatch(c -> Character.digit(c, 16) >= 0 || c == '.' || c == ':')) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(value).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> headerLines(HttpServletRequest request, String name) {
        var values = request.getHeaders(name);
        return values == null ? List.of() : Collections.list(values);
    }
}
