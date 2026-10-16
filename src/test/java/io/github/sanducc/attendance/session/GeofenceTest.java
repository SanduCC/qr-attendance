package io.github.sanducc.attendance.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.sanducc.attendance.config.AttendanceProperties.GeofenceSettings;
import io.github.sanducc.attendance.session.Geofence.Location;
import io.github.sanducc.attendance.session.Geofence.Result;
import org.junit.jupiter.api.Test;

/** Rule 6: haversine distance, the accuracy rule and the bounds. */
class GeofenceTest {

    /** Universitatea Politehnica din București, main building (approximate). */
    private static final double LAT = 44.4386;
    private static final double LNG = 26.0494;
    /** One metre of latitude, in degrees (mean Earth radius). */
    private static final double DEG_PER_M = 1 / (Math.toRadians(1) * Geofence.EARTH_RADIUS_M);
    private static final double CAP = 100;

    private final Geofence fence = new Geofence(LAT, LNG, 100);

    private static Location metresNorth(double metres, Double accuracy) {
        return new Location(LAT + metres * DEG_PER_M, LNG, accuracy);
    }

    // ------------------------------------------------------------------ haversine

    @Test
    void haversineMatchesKnownDistances() {
        assertThat(Geofence.distanceMeters(LAT, LNG, LAT, LNG)).isZero();
        // One degree along the equator / a meridian = 2πR / 360.
        assertThat(Geofence.distanceMeters(0, 0, 0, 1)).isCloseTo(111_195.08, within(0.5));
        assertThat(Geofence.distanceMeters(0, 0, 1, 0)).isCloseTo(111_195.08, within(0.5));
        // Bucharest (Piața Universității) to Cluj-Napoca (Piața Unirii): about 324 km as the crow flies.
        assertThat(Geofence.distanceMeters(44.4355, 26.1025, 46.7694, 23.5899)).isCloseTo(324_000, within(2_000.0));
        // Antipodes: half the circumference, no NaN from rounding.
        assertThat(Geofence.distanceMeters(0, 0, 0, 180)).isCloseTo(Math.PI * Geofence.EARTH_RADIUS_M, within(1.0));
        // Symmetric, and a degree of longitude shrinks with latitude.
        assertThat(Geofence.distanceMeters(LAT, LNG, 45, 27)).isEqualTo(Geofence.distanceMeters(45, 27, LAT, LNG));
        assertThat(Geofence.distanceMeters(LAT, 0, LAT, 1)).isCloseTo(111_195.08 * Math.cos(Math.toRadians(LAT)),
                within(1.0));
    }

    @Test
    void classroomScaleDistancesAreAccurateToTheMetre() {
        assertThat(Geofence.distanceMeters(LAT, LNG, LAT + 50 * DEG_PER_M, LNG)).isCloseTo(50, within(0.01));
        assertThat(Geofence.distanceMeters(LAT, LNG, LAT + 150 * DEG_PER_M, LNG)).isCloseTo(150, within(0.01));
    }

    // ------------------------------------------------------------------ accuracy rule

    @Test
    void insideTheRadiusPassesWhateverTheAccuracy() {
        assertThat(fence.check(metresNorth(0, null), CAP)).isInstanceOf(Result.Inside.class);
        assertThat(fence.check(metresNorth(99, 0.0), CAP)).isInstanceOf(Result.Inside.class);
        assertThat(fence.check(metresNorth(99.9, 5000.0), CAP)).isInstanceOf(Result.Inside.class);
    }

    @Test
    void reportedAccuracyIsCreditedUpToTheCap() {
        // 140 m away, ±50 m: 140 - 50 = 90 <= 100.
        assertThat(fence.check(metresNorth(140, 50.0), CAP)).isInstanceOf(Result.Inside.class);
        // 140 m away, ±30 m: 110 > 100.
        assertThat(fence.check(metresNorth(140, 30.0), CAP)).isInstanceOf(Result.Outside.class);
        // Just on the edge after the credit counts as inside.
        assertThat(fence.check(metresNorth(149.99, 50.0), CAP)).isInstanceOf(Result.Inside.class);
        assertThat(fence.check(metresNorth(150.01, 50.0), CAP)).isInstanceOf(Result.Outside.class);
    }

    @Test
    void aHugeClaimedAccuracyCannotStretchTheFenceBeyondTheCap() {
        // ±5 km claimed, credited 100 m: 199 m passes, 201 m doesn't, 3 km certainly doesn't.
        assertThat(fence.check(metresNorth(199, 5000.0), CAP)).isInstanceOf(Result.Inside.class);
        assertThat(fence.check(metresNorth(201, 5000.0), CAP)).isInstanceOf(Result.Outside.class);
        assertThat(fence.check(metresNorth(3000, 1e9), CAP)).isInstanceOf(Result.Outside.class);
        // A cap of 0 means "no credit at all".
        assertThat(fence.check(metresNorth(101, 50.0), 0)).isInstanceOf(Result.Outside.class);
    }

    @Test
    void missingNegativeOrNonFiniteAccuracyEarnsNoCredit() {
        assertThat(fence.check(metresNorth(120, null), CAP)).isInstanceOf(Result.Outside.class);
        assertThat(fence.check(metresNorth(120, -50.0), CAP)).isInstanceOf(Result.Outside.class);
        assertThat(fence.check(metresNorth(120, Double.NaN), CAP)).isInstanceOf(Result.Outside.class);
        assertThat(fence.check(metresNorth(120, Double.POSITIVE_INFINITY), CAP)).isInstanceOf(Result.Outside.class);
    }

    @Test
    void outsideReportsTheRawDistance() {
        Result result = fence.check(metresNorth(350, 20.0), CAP);
        assertThat(result).isInstanceOf(Result.Outside.class);
        assertThat(((Result.Outside) result).distanceMeters()).isCloseTo(350, within(0.1));
    }

    @Test
    void aMissingOrInvalidPositionIsMissing() {
        assertThat(fence.check(null, CAP)).isInstanceOf(Result.Missing.class);
        assertThat(fence.check(Location.NONE, CAP)).isInstanceOf(Result.Missing.class);
        assertThat(fence.check(new Location(LAT, null, 10.0), CAP)).isInstanceOf(Result.Missing.class);
        assertThat(fence.check(new Location(91.0, LNG, 10.0), CAP)).isInstanceOf(Result.Missing.class);
        assertThat(fence.check(new Location(LAT, -180.5, 10.0), CAP)).isInstanceOf(Result.Missing.class);
        assertThat(fence.check(new Location(Double.NaN, LNG, 10.0), CAP)).isInstanceOf(Result.Missing.class);
    }

    // ------------------------------------------------------------------ parsing and bounds

    @Test
    void formValuesAreParsedLeniently() {
        assertThat(Location.parse("44.4386", "26.0494", "35")).isEqualTo(new Location(44.4386, 26.0494, 35.0));
        assertThat(Location.parse(" 44.4386 ", "26.0494", null)).isEqualTo(new Location(44.4386, 26.0494, null));
        assertThat(Location.parse("", "26.0494", "x")).isEqualTo(new Location(null, 26.0494, null));
        assertThat(Location.parse("NaN", "Infinity", "1e400")).isEqualTo(Location.NONE);
        assertThat(Location.parse("abc", "1".repeat(40), "")).isEqualTo(Location.NONE);
        assertThat(Location.parse("44.4", "26.0", "10").isPresent()).isTrue();
        assertThat(Location.parse("44.4", "", "10").isPresent()).isFalse();
    }

    @Test
    void theCentreMustBeARealPlaceAndTheRadiusPositive() {
        assertThat(Geofence.isValidCoordinate(-90, -180)).isTrue();
        assertThat(Geofence.isValidCoordinate(90, 180)).isTrue();
        assertThat(Geofence.isValidCoordinate(90.0001, 0)).isFalse();
        assertThat(Geofence.isValidCoordinate(0, Double.NaN)).isFalse();
        assertThatThrownBy(() -> new Geofence(100, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Geofence(LAT, LNG, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void radiusBoundsMustBeConsistent() {
        assertThat(new GeofenceSettings(100, 25, 1000, 100).defaultRadiusM()).isEqualTo(100);
        assertThatThrownBy(() -> new GeofenceSettings(10, 25, 1000, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeofenceSettings(2000, 25, 1000, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeofenceSettings(100, 500, 200, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
