package io.github.sanducc.attendance.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Spring's {@link ForwardedHeaderFilter} (what {@code server.forward-headers-strategy=framework} installs), except
 * for the client address (issue #39).
 *
 * <p>Scheme, host and port still come from Spring's handling of {@code X-Forwarded-Proto} / {@code -Host} /
 * {@code -Port} / {@code Forwarded}, unchanged: that's what makes OAuth redirect URIs {@code https://…run.app/…},
 * {@code request.isSecure()} true and HSTS sent behind Cloud Run. The forwarded headers are hidden from the rest of
 * the app as before.
 *
 * <p>Spring takes {@code getRemoteAddr()} from the <b>left-most</b> {@code Forwarded: for=} or
 * {@code X-Forwarded-For} value, and both can be written by the client: Cloud Run appends its entries to whatever the
 * client sent. Here {@code getRemoteAddr()} / {@code getRemoteHost()} is instead {@link ForwardedClientIp}: the
 * {@code X-Forwarded-For} entry the trusted proxy added, counted from the right. That's the address the per-IP rate
 * limits key on and {@code attendance_record.ip_hash} hashes.
 */
public class TrustedProxyForwardedHeaderFilter extends ForwardedHeaderFilter {

    private final int trustedProxyHops;

    public TrustedProxyForwardedHeaderFilter(int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("trustedProxyHops must be >= 0");
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Read from the raw request: Spring's wrapper hides the forwarded headers.
        String clientIp = ForwardedClientIp.resolve(request, trustedProxyHops);
        super.doFilterInternal(request, response, (wrappedRequest, wrappedResponse) ->
                chain.doFilter(new ClientIpRequest((HttpServletRequest) wrappedRequest, clientIp), wrappedResponse));
    }

    private static final class ClientIpRequest extends HttpServletRequestWrapper {

        private final String clientIp;

        ClientIpRequest(HttpServletRequest request, String clientIp) {
            super(request);
            this.clientIp = clientIp;
        }

        @Override
        public String getRemoteAddr() {
            return clientIp;
        }

        @Override
        public String getRemoteHost() {
            return clientIp;
        }
    }
}
