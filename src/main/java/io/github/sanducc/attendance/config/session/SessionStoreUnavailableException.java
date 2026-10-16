package io.github.sanducc.attendance.config.session;

/**
 * Redis, which holds the HTTP sessions, can't be reached. Sessions fail closed: the request is answered with a
 * "try again in a minute" page by {@link SessionStoreUnavailableFilter} instead of continuing without a session.
 */
public class SessionStoreUnavailableException extends RuntimeException {

    public SessionStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
