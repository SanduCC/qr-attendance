package io.github.sanducc.attendance.checkin;

import java.time.Instant;

/** A check-in joined with the student's name, for the teacher table and CSV export. */
public record RecordView(Long id, String firstName, String lastName, String email, Instant checkedInAt) {}
