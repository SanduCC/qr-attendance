package io.github.sanducc.attendance.config.session;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * Wraps the Redis session repository with three behaviours:
 * <ol>
 *   <li><b>Unreadable session = no session.</b> If a stored session can't be read back (a value written by an
 *       older version of the app, a corrupt or foreign blob), it is deleted and treated as absent: the user is
 *       asked to sign in again. It never turns into a 500 or an error loop.</li>
 *   <li><b>Redis down = fail closed.</b> Store errors become {@link SessionStoreUnavailableException}, which
 *       {@link SessionStoreUnavailableFilter} turns into a 503 "try again" page. A request never silently
 *       continues without its session (that would lose a pending check-in or a sign-in).</li>
 *   <li><b>Fewer writes.</b> Spring Session rewrites the session on every request just to bump its last-access
 *       time (EXISTS + HSET + EXPIREAT). The projector page polls every few seconds, so that would triple the
 *       Redis commands for nothing. Here a request that changed nothing only refreshes the last-access time
 *       (and so the expiry) when the stored one is at least {@code touchInterval} old. Read-only polling then
 *       costs one HGETALL per request. Any real change (attribute, id rotation, timeout) is saved as before.</li>
 * </ol>
 */
public final class ResilientSessionRepository implements SessionRepository<ResilientSessionRepository.StoredSession> {

    private static final Logger log = LoggerFactory.getLogger(ResilientSessionRepository.class);

    private final SessionRepository<Session> delegate;
    private final Duration touchInterval;

    @SuppressWarnings("unchecked")
    public ResilientSessionRepository(SessionRepository<? extends Session> delegate, Duration touchInterval) {
        // The delegate only ever sees sessions it created itself (unwrapped in save), so the cast is safe.
        this.delegate = (SessionRepository<Session>) delegate;
        this.touchInterval = touchInterval;
    }

    @Override
    public StoredSession createSession() {
        try {
            return new StoredSession(delegate.createSession(), true);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    @Override
    public void save(StoredSession session) {
        if (!session.isNew && !session.dirty) {
            return; // nothing changed and the last-access time is fresh enough: no Redis round trip
        }
        if (session.pendingTouch != null) {
            session.delegate.setLastAccessedTime(session.pendingTouch); // free: rides along with this write
            session.pendingTouch = null;
        }
        try {
            delegate.save(session.delegate);
            session.isNew = false;
            session.dirty = false;
        } catch (IllegalStateException e) {
            // RedisSessionRepository: "Session was invalidated" (signed out or expired while this request ran).
            // Don't resurrect it; the next request simply has no session.
            log.debug("Not saving HTTP session that was invalidated meanwhile: {}", e.getMessage());
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    @Override
    public StoredSession findById(String id) {
        Session found;
        try {
            found = delegate.findById(id);
        } catch (DataAccessException e) {
            throw unavailable(e);
        } catch (RuntimeException e) {
            // e.g. RedisSessionMapper casting an unreadable creationTime, or a missing mandatory field
            return drop(id, e.getClass().getSimpleName());
        }
        if (found == null) {
            return null;
        }
        for (String name : found.getAttributeNames()) {
            if (found.getAttribute(name) == SessionRedisSerializer.UNREADABLE) {
                return drop(id, "attribute '" + name + "'");
            }
        }
        return new StoredSession(found, false);
    }

    @Override
    public void deleteById(String id) {
        try {
            delegate.deleteById(id);
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private StoredSession drop(String id, String what) {
        // Never log the id itself: it's the session cookie value.
        log.warn("Dropping an HTTP session that can't be read back ({}); the user will be asked to sign in again",
                what);
        deleteById(id);
        return null;
    }

    private static SessionStoreUnavailableException unavailable(DataAccessException e) {
        return new SessionStoreUnavailableException("HTTP session store (Redis) unavailable: " + e.getMessage(), e);
    }

    /** A delegate session that tracks whether this request changed anything worth writing. */
    public final class StoredSession implements Session {

        private final Session delegate;
        private boolean isNew;
        private boolean dirty;
        private Instant pendingTouch;

        private StoredSession(Session delegate, boolean isNew) {
            this.delegate = delegate;
            this.isNew = isNew;
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public String changeSessionId() {
            dirty = true;
            return delegate.changeSessionId();
        }

        @Override
        public <T> T getAttribute(String attributeName) {
            return delegate.getAttribute(attributeName);
        }

        @Override
        public Set<String> getAttributeNames() {
            return delegate.getAttributeNames();
        }

        @Override
        public void setAttribute(String attributeName, Object attributeValue) {
            dirty = true;
            delegate.setAttribute(attributeName, attributeValue);
        }

        @Override
        public void removeAttribute(String attributeName) {
            dirty = true;
            delegate.removeAttribute(attributeName);
        }

        @Override
        public Instant getCreationTime() {
            return delegate.getCreationTime();
        }

        @Override
        public void setLastAccessedTime(Instant lastAccessedTime) {
            Duration sinceStored = Duration.between(delegate.getLastAccessedTime(), lastAccessedTime);
            if (isNew || sinceStored.compareTo(touchInterval) >= 0) {
                dirty = true;
                pendingTouch = null;
                delegate.setLastAccessedTime(lastAccessedTime);
            } else {
                // Only written if something else makes this request save anyway.
                pendingTouch = lastAccessedTime;
            }
        }

        @Override
        public Instant getLastAccessedTime() {
            return delegate.getLastAccessedTime();
        }

        @Override
        public void setMaxInactiveInterval(Duration interval) {
            dirty = true;
            delegate.setMaxInactiveInterval(interval);
        }

        @Override
        public Duration getMaxInactiveInterval() {
            return delegate.getMaxInactiveInterval();
        }

        @Override
        public boolean isExpired() {
            return delegate.isExpired();
        }
    }
}
