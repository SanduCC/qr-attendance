package io.github.sanducc.attendance.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class RetentionPolicyTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final MonthDay SEPT_30 = MonthDay.of(9, 30);
    private static final int GRACE = 30;

    @Test
    void duringTheGracePeriodLastYearsDataIsKept() {
        // Academic year 2025/26 ended 2026-09-30; teachers have until 2026-10-31 to export it.
        Instant cutoff = cutoff("2026-10-16T09:00:00Z");

        assertThat(cutoff).isEqualTo(startOfDay("2025-10-01"));
    }

    @Test
    void theDayBeforeTheGracePeriodEndsNothingNewIsPurged() {
        assertThat(cutoff("2026-10-30T09:00:00Z")).isEqualTo(startOfDay("2025-10-01"));
    }

    @Test
    void onceTheGracePeriodHasElapsedTheFinishedYearIsPurged() {
        assertThat(cutoff("2026-10-31T09:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
        assertThat(cutoff("2026-11-15T09:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
    }

    @Test
    void midYearKeepsTheCurrentAndPreviousYearBoundary() {
        // March 2027: the 2026/27 year is in progress; everything before 2026-10-01 is the finished year.
        assertThat(cutoff("2027-03-01T09:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
        // Just before the year ends.
        assertThat(cutoff("2027-09-30T20:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
    }

    @Test
    void acrossTheCalendarYearBoundary() {
        assertThat(cutoff("2026-12-31T12:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
        assertThat(cutoff("2027-01-01T12:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
        assertThat(cutoff("2027-01-05T12:00:00Z")).isEqualTo(startOfDay("2026-10-01"));
    }

    @Test
    void usesTheLocalDateInTheConfiguredZone() {
        // 22:30 UTC on Oct 30 is already 00:30 on Oct 31 in Bucharest (EET, UTC+2 after the DST switch).
        Instant now = Instant.parse("2026-10-30T22:30:00Z");

        assertThat(RetentionPolicy.purgeCutoff(now, BUCHAREST, SEPT_30, GRACE)).isEqualTo(startOfDay("2026-10-01"));
        assertThat(RetentionPolicy.purgeCutoff(now, ZoneId.of("UTC"), SEPT_30, GRACE))
                .isEqualTo(LocalDate.parse("2025-10-01").atStartOfDay(ZoneId.of("UTC")).toInstant());
    }

    @Test
    void cutoffIsMidnightInTheConfiguredZone() {
        // 2026-10-01 00:00 in Bucharest (EEST, UTC+3).
        assertThat(cutoff("2026-11-15T09:00:00Z")).isEqualTo(Instant.parse("2026-09-30T21:00:00Z"));
    }

    @Test
    void zeroGraceDaysPurgesOnTheFirstDayOfTheNewYear() {
        Instant now = Instant.parse("2026-10-01T06:00:00Z");

        assertThat(RetentionPolicy.purgeCutoff(now, BUCHAREST, SEPT_30, 0)).isEqualTo(startOfDay("2026-10-01"));
        assertThat(RetentionPolicy.purgeCutoff(now.minusSeconds(86_400), BUCHAREST, SEPT_30, 0))
                .isEqualTo(startOfDay("2025-10-01"));
    }

    @Test
    void calendarYearEndOutsideTheGracePeriod() {
        MonthDay dec31 = MonthDay.of(12, 31);

        assertThat(RetentionPolicy.purgeCutoff(Instant.parse("2026-03-01T09:00:00Z"), BUCHAREST, dec31, GRACE))
                .isEqualTo(startOfDay("2026-01-01"));
    }

    @Test
    void neverPurgesDataStillInsideItsGracePeriodForSeptemberYearEnd() {
        assertInvariantForEveryDay(SEPT_30, GRACE);
    }

    /**
     * Regression (#18): with a December 31 year end the first candidate (Jan 1 next year) is always in
     * the future; trying only one fallback year made the cutoff 2026-01-01 in January, purging all of
     * 2025 while it was still inside its grace period.
     */
    @Test
    void calendarYearEndInsideTheGracePeriodKeepsLastYear() {
        Instant cutoff = RetentionPolicy.purgeCutoff(Instant.parse("2026-01-15T09:00:00Z"), BUCHAREST,
                MonthDay.of(12, 31), GRACE);

        assertThat(cutoff).isEqualTo(startOfDay("2025-01-01"));
    }

    @Test
    void neverPurgesDataStillInsideItsGracePeriodForDecemberYearEnd() {
        assertInvariantForEveryDay(MonthDay.of(12, 31), GRACE);
    }

    @Test
    void neverPurgesDataStillInsideAGracePeriodLongerThanAYear() {
        assertInvariantForEveryDay(SEPT_30, 400);
    }

    /**
     * For every day over two years: the cutoff is the start of an academic year whose predecessor's
     * grace period is over, and it is the most recent such start.
     */
    private static void assertInvariantForEveryDay(MonthDay yearEnd, int grace) {
        for (LocalDate today = LocalDate.parse("2026-01-01"); today.isBefore(LocalDate.parse("2028-01-01"));
                today = today.plusDays(1)) {
            Instant now = today.atTime(12, 0).atZone(BUCHAREST).toInstant();
            LocalDate cutoff = LocalDate.ofInstant(RetentionPolicy.purgeCutoff(now, BUCHAREST, yearEnd, grace),
                    BUCHAREST);

            assertThat(MonthDay.from(cutoff.minusDays(1))).as("cutoff follows a year end, on %s", today)
                    .isEqualTo(yearEnd);
            assertThat(cutoff.plusDays(grace)).as("grace elapsed for data before the cutoff, on %s", today)
                    .isBeforeOrEqualTo(today);
            LocalDate nextStart = cutoff.plusYears(1);
            assertThat(nextStart.plusDays(grace)).as("cutoff is the most recent possible, on %s", today)
                    .isAfter(today);
        }
    }

    private static Instant cutoff(String now) {
        return RetentionPolicy.purgeCutoff(Instant.parse(now), BUCHAREST, SEPT_30, GRACE);
    }

    private static Instant startOfDay(String date) {
        return LocalDate.parse(date).atStartOfDay(BUCHAREST).toInstant();
    }
}
