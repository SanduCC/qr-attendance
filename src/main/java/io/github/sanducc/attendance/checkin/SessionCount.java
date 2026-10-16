package io.github.sanducc.attendance.checkin;

import java.util.UUID;

/** Number of check-ins in one session (JPQL constructor result, hence the boxed count). */
public record SessionCount(UUID sessionId, Long checkedIn) {}
