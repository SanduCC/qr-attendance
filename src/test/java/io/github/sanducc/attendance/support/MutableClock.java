package io.github.sanducc.attendance.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A clock tests can move. Integration tests get it as the {@code @Primary} Clock bean, so every
 * service that captured "the clock" by constructor injection sees the same, controllable time.
 */
public class MutableClock extends Clock {

    /**
     * 2026-10-16 09:00:05 in Europe/Bucharest (EEST, UTC+3). Five seconds into a 15 s QR window,
     * so "+15 s" is the next window and "+30 s" is two windows later.
     */
    public static final Instant DEFAULT_START = Instant.parse("2026-10-16T06:00:05Z");

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock() {
        this(new AtomicReference<>(DEFAULT_START), ZoneOffset.UTC);
    }

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // Shares the same time source, so moving one moves both.
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public long millis() {
        return now.get().toEpochMilli();
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration duration) {
        now.updateAndGet(i -> i.plus(duration));
    }

    public void reset() {
        now.set(DEFAULT_START);
    }
}
