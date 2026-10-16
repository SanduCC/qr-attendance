package io.github.sanducc.attendance.config.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sits just outside Spring Session's filter. When the session store is down
 * ({@link SessionStoreUnavailableException} anywhere in the cause chain) it answers 503 with a short static page
 * and {@code Retry-After}, instead of a 500 stack trace or an error page that would itself need the session.
 * Requests that don't touch the session (static files, {@code /actuator/health}, a first visit without a session
 * cookie) never get here.
 */
public class SessionStoreUnavailableFilter extends OncePerRequestFilter {

    static final int RETRY_AFTER_SECONDS = 30;

    private static final Logger log = LoggerFactory.getLogger(SessionStoreUnavailableFilter.class);

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Temporarily unavailable</title>
            <link rel="stylesheet" href="/css/app.css">
            </head>
            <body>
            <main class="container">
            <div class="card center">
            <p class="status-code">503</p>
            <h1>Temporarily unavailable</h1>
            <p class="muted">Sign-in and check-in are briefly unavailable. Please try again in a minute.</p>
            <a class="btn btn-primary" href="/">Go to home page</a>
            </div>
            </main>
            </body>
            </html>
            """;

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false; // an error page rendered while Redis is down needs the same treatment
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            SessionStoreUnavailableException unavailable = findCause(e);
            if (unavailable == null) {
                throw e;
            }
            log.warn("{} {} failed: {}", request.getMethod(), request.getRequestURI(), unavailable.getMessage());
            if (response.isCommitted()) {
                return; // too late for a clean page; the client sees a truncated response
            }
            response.reset();
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'self'; frame-ancestors 'none'");
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(PAGE);
        }
    }

    static SessionStoreUnavailableException findCause(Throwable e) {
        Throwable current = e;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof SessionStoreUnavailableException unavailable) {
                return unavailable;
            }
            current = current.getCause();
        }
        return null;
    }
}
