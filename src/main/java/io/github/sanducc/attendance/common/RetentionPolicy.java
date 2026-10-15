package io.github.sanducc.attendance.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;

/**
 * GDPR retention: attendance is kept until the end of the academic year, plus a grace period
 * for teachers to export. Data from sessions opened before the returned cutoff gets purged.
 */
public final class RetentionPolicy {

    private RetentionPolicy() {
    }

    /**
     * @param academicYearEnd last day of the academic year (e.g. 09-30)
     * @return the start of the most recent academic year whose grace period has fully elapsed
     */
    public static Instant purgeCutoff(Instant now, ZoneId zone, MonthDay academicYearEnd, int graceDays) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        // Step back a year at a time: with a year end late in the calendar year (or a long grace
        // period) a single step back can still land inside the grace period.
        int year = today.getYear();
        LocalDate yearStart;
        do {
            yearStart = academicYearEnd.atYear(year--).plusDays(1);
        } while (yearStart.plusDays(graceDays).isAfter(today));
        return yearStart.atStartOfDay(zone).toInstant();
    }
}
