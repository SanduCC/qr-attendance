package io.github.sanducc.attendance.stats;

import java.time.Instant;

/**
 * A course's sessions in one row (JPQL constructor result, hence the boxed counts): how many there are, how many have
 * at least one entry, and when the first and last opened (both null without sessions).
 */
public record SessionTotals(Long sessions, Long sessionsWithEntries, Instant firstOpenedAt, Instant lastOpenedAt) {}
