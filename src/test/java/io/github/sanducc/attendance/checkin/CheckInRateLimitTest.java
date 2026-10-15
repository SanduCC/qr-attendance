package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Anti-cheat rule 8, with a tiny per-device limit so it's cheap to hit. Uses its own Spring context. */
@TestPropertySource(properties = "attendance.rate-limit.per-device-per-minute=3")
class CheckInRateLimitTest extends IntegrationTestSupport {

    @Test
    void fourthScanFromTheSameDeviceWithinAMinuteIsRateLimited() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();

        for (int i = 1; i <= 3; i++) {
            scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        }
        scan(phone, f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isTooManyRequests())
                .andExpect(view().name("student/message"))
                .andExpect(model().attribute("title", "Too many attempts"));
    }

    @Test
    void otherDevicesAreNotAffected() throws Exception {
        Fixture f = openFixture();
        Browser noisy = newBrowser();
        for (int i = 1; i <= 4; i++) {
            scan(noisy, f.sessionId(), validToken(f.sessionId()));
        }

        scan(newBrowser(), f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
    }

    @Test
    void limitResetsInTheNextMinute() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        for (int i = 1; i <= 4; i++) {
            scan(phone, f.sessionId(), validToken(f.sessionId()));
        }
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isTooManyRequests());

        clock.advance(Duration.ofMinutes(1));

        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
    }

    @Test
    void checkInAttemptsCountTowardsTheSameLimit() throws Exception {
        Fixture f = openFixture();
        Browser phone = newBrowser();
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        scan(phone, f.sessionId(), validToken(f.sessionId())).andExpect(status().isOk());
        // Attempt 3 of 3 is allowed and succeeds.
        postCheckIn(phone, f.sessionId(), studentLogin("sub-1", "Ana", "Pop"), "Ana", "Pop")
                .andExpect(status().is3xxRedirection());

        // Attempt 4 is refused by the limiter before any other rule is evaluated.
        postCheckIn(phone, f.sessionId(), studentLogin("sub-2", "Bogdan", "Ionescu"), "Bogdan", "Ionescu")
                .andExpect(status().isTooManyRequests())
                .andExpect(view().name("student/message"));

        assertThat(recordRepository.countBySessionId(f.sessionId())).isEqualTo(1);
    }
}
