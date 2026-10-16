package io.github.sanducc.attendance.checkin;

import java.time.Instant;

/**
 * A check-in joined with the person's name, for the teacher table and the exports. {@code studentId} and
 * {@code email} are null for a manual entry of someone without an account (the name then comes from the record).
 */
public record RecordView(Long id, Long studentId, String firstName, String lastName, String email,
                         Instant checkedInAt, boolean manual) {}
