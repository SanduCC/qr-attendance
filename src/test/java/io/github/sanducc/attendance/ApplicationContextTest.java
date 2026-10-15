package io.github.sanducc.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanducc.attendance.checkin.CheckInService;
import io.github.sanducc.attendance.common.RateLimiter;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** Context starts: Flyway migrates the real Postgres and Hibernate {@code ddl-auto: validate} accepts it. */
class ApplicationContextTest extends IntegrationTestSupport {

    @Autowired CheckInService checkInService;
    @Autowired RateLimiter rateLimiter;
    @Autowired Clock injectedClock;

    @Test
    void contextLoadsAndFlywayMigrated() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and version = '1'", Integer.class);
        assertThat(applied).isEqualTo(1);

        Integer constraints = jdbc.queryForObject("""
                select count(*) from information_schema.table_constraints
                where table_name = 'attendance_record' and constraint_type = 'UNIQUE'
                  and constraint_name in ('uq_record_session_student', 'uq_record_session_device')
                """, Integer.class);
        assertThat(constraints).isEqualTo(2);
    }

    @Test
    void theMutableClockIsTheOneInjectedEverywhere() {
        assertThat(injectedClock).isSameAs(clock);
        assertThat(ReflectionTestUtils.getField(qrTokens, "clock")).isSameAs(clock);
        assertThat(ReflectionTestUtils.getField(rateLimiter, "clock")).isSameAs(clock);
        assertThat(ReflectionTestUtils.getField(checkInService, "clock")).isSameAs(clock);
        assertThat(ReflectionTestUtils.getField(unwrap(sessionService), "clock")).isSameAs(clock);
    }

    private static Object unwrap(Object bean) {
        // SessionService is @Transactional, so it may be a CGLIB proxy.
        return AopTestUtils.getUltimateTargetObject(bean);
    }
}
