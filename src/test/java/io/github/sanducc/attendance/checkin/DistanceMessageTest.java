package io.github.sanducc.attendance.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The "you seem to be about … from the classroom" wording (rule 6) doesn't pretend to GPS precision. */
class DistanceMessageTest {

    @Test
    void distancesAreRounded() {
        assertThat(ScanController.approximately(0)).isEqualTo("about 10 m");
        assertThat(ScanController.approximately(104)).isEqualTo("about 100 m");
        assertThat(ScanController.approximately(248)).isEqualTo("about 250 m");
        assertThat(ScanController.approximately(999)).isEqualTo("about 1000 m");
        assertThat(ScanController.approximately(1000)).isEqualTo("about 1.0 km");
        assertThat(ScanController.approximately(23_456)).isEqualTo("about 23.5 km");
    }
}
