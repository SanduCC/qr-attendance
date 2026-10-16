package io.github.sanducc.attendance.config.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.sanducc.attendance.config.session.ResilientSessionRepository.StoredSession;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;

/** Unreadable session = no session, Redis down = fail closed, and no rewrite on read-only requests. */
class ResilientSessionRepositoryTest {

    private static final Duration TOUCH = Duration.ofMinutes(1);

    private MapSessionRepository store;
    private ResilientSessionRepository repository;

    @BeforeEach
    void setUp() {
        store = spy(new MapSessionRepository(new ConcurrentHashMap<>()));
        repository = new ResilientSessionRepository(store, TOUCH);
    }

    private String storedSession() {
        StoredSession session = repository.createSession();
        session.setAttribute("a", "1");
        repository.save(session);
        clearInvocations(store);
        return session.getId();
    }

    // ------------------------------------------------------------------ write budget

    @Test
    void newSessionsAreSaved() {
        StoredSession session = repository.createSession();
        repository.save(session);

        verify(store).save(any());
        assertThat(store.findById(session.getId())).isNotNull();
    }

    @Test
    void aReadOnlyRequestWithinTheTouchIntervalWritesNothing() {
        String id = storedSession();
        StoredSession session = repository.findById(id);
        Instant stored = session.getLastAccessedTime();

        session.setLastAccessedTime(stored.plusSeconds(30));
        session.getAttribute("a");
        repository.save(session);

        verify(store, never()).save(any());
        assertThat(store.findById(id).getLastAccessedTime()).isEqualTo(stored);
    }

    @Test
    void aReadOnlyRequestAfterTheTouchIntervalRefreshesTheLastAccessTime() {
        String id = storedSession();
        StoredSession session = repository.findById(id);
        Instant later = session.getLastAccessedTime().plus(TOUCH);

        session.setLastAccessedTime(later);
        repository.save(session);

        verify(store, times(1)).save(any());
        assertThat(store.findById(id).getLastAccessedTime()).isEqualTo(later);
    }

    @Test
    void anAttributeChangeIsSavedTogetherWithThePendingTouch() {
        String id = storedSession();
        StoredSession session = repository.findById(id);
        Instant soon = session.getLastAccessedTime().plusSeconds(5);

        session.setLastAccessedTime(soon);
        session.setAttribute("b", 2L);
        repository.save(session);

        MapSession saved = store.findById(id);
        assertThat(saved.<Long>getAttribute("b")).isEqualTo(2L);
        assertThat(saved.getLastAccessedTime()).isEqualTo(soon);
    }

    @Test
    void removingAnAttributeAndRotatingTheIdAreSaved() {
        String id = storedSession();
        StoredSession session = repository.findById(id);
        session.removeAttribute("a");
        String newId = session.changeSessionId();
        repository.save(session);

        assertThat(store.findById(id)).isNull();
        assertThat(store.findById(newId)).isNotNull();
        assertThat(store.findById(newId).<String>getAttribute("a")).isNull();
    }

    // ------------------------------------------------------------------ unreadable = no session

    @Test
    void aSessionWithAnUnreadableAttributeIsDroppedAndDeleted() {
        MapSession broken = new MapSession();
        broken.setAttribute("SPRING_SECURITY_CONTEXT", SessionRedisSerializer.UNREADABLE);
        store.save(broken);

        assertThat(repository.findById(broken.getId())).isNull();
        assertThat(store.findById(broken.getId())).as("deleted").isNull();
    }

    @Test
    void aSessionTheStoreCannotMapIsDroppedAndDeleted() {
        @SuppressWarnings("unchecked")
        SessionRepository<MapSession> failing = mock(SessionRepository.class);
        when(failing.findById("id")).thenThrow(new ClassCastException("UNREADABLE cannot be cast to Long"));
        ResilientSessionRepository resilient = new ResilientSessionRepository(failing, TOUCH);

        assertThat(resilient.findById("id")).isNull();
        verify(failing).deleteById("id");
    }

    @Test
    void aSessionInvalidatedWhileTheRequestRanIsNotResurrected() {
        @SuppressWarnings("unchecked")
        SessionRepository<MapSession> failing = mock(SessionRepository.class);
        when(failing.findById("id")).thenReturn(new MapSession("id"));
        doThrow(new IllegalStateException("Session was invalidated")).when(failing).save(any());
        ResilientSessionRepository resilient = new ResilientSessionRepository(failing, TOUCH);
        StoredSession session = resilient.findById("id");
        session.setAttribute("x", 1);

        assertThatCode(() -> resilient.save(session)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ Redis down = fail closed

    @Test
    void storeErrorsBecomeSessionStoreUnavailable() {
        @SuppressWarnings("unchecked")
        SessionRepository<MapSession> down = mock(SessionRepository.class);
        RedisConnectionFailureException failure = new RedisConnectionFailureException("Unable to connect to Redis");
        when(down.findById(anyString())).thenThrow(failure);
        doThrow(failure).when(down).deleteById(anyString());
        when(down.createSession()).thenReturn(new MapSession());
        doThrow(failure).when(down).save(any());
        ResilientSessionRepository resilient = new ResilientSessionRepository(down, TOUCH);

        assertThatThrownBy(() -> resilient.findById("id")).isInstanceOf(SessionStoreUnavailableException.class)
                .hasCause(failure);
        assertThatThrownBy(() -> resilient.deleteById("id")).isInstanceOf(SessionStoreUnavailableException.class);
        StoredSession created = resilient.createSession();
        assertThatThrownBy(() -> resilient.save(created)).isInstanceOf(SessionStoreUnavailableException.class);
    }
}
