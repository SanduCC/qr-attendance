package io.github.sanducc.attendance.session;

/**
 * Anti-cheat rule 6 (optional, off by default): the classroom as a circle, captured from the teacher's device when
 * the session starts, and the check of a student's browser-reported position against it.
 *
 * <p><b>Accuracy rule.</b> Browsers report a position plus an accuracy radius (95% confidence, in metres). A student
 * passes when {@code distance - min(accuracy, maxAccuracyCredit) <= radius}: a phone that honestly says "I'm within
 * 60 m of here" gets the benefit of those 60 m, but never more than the configured cap, so claiming a huge accuracy
 * can't stretch the fence. A missing, negative or non-finite accuracy counts as 0 (strictest).
 *
 * <p><b>It's a deterrent, not proof.</b> The position comes from the student's browser and can be faked (developer
 * tools, mock-location apps). It stops the casual "check in from home", like the device cookie does; the headcount
 * check and the teacher's eyes remain the real guard. Student coordinates are only compared, never stored.
 */
public record Geofence(double latitude, double longitude, int radiusMeters) {

    /** Mean Earth radius (IUGG), metres. */
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    public Geofence {
        if (!isValidCoordinate(latitude, longitude)) {
            throw new IllegalArgumentException("Invalid geofence centre");
        }
        if (radiusMeters <= 0) {
            throw new IllegalArgumentException("Geofence radius must be positive");
        }
    }

    /** What the student's browser reported; any part may be missing. */
    public record Location(Double latitude, Double longitude, Double accuracyMeters) {

        public static final Location NONE = new Location(null, null, null);

        /** Lenient parsing of form fields: anything unparseable is treated as missing. */
        public static Location parse(String latitude, String longitude, String accuracy) {
            return new Location(parseDouble(latitude), parseDouble(longitude), parseDouble(accuracy));
        }

        public boolean isPresent() {
            return latitude != null && longitude != null && isValidCoordinate(latitude, longitude);
        }

        private static Double parseDouble(String value) {
            if (value == null || value.isBlank() || value.length() > 32) {
                return null;
            }
            try {
                double d = Double.parseDouble(value.strip());
                return Double.isFinite(d) ? d : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public sealed interface Result {
        /** {@code distanceMeters} is from the reported position to the centre, before the accuracy credit. */
        record Inside(double distanceMeters) implements Result {}
        record Outside(double distanceMeters) implements Result {}
        /** No usable position was sent although this session requires one. */
        record Missing() implements Result {}
    }

    public Result check(Location reported, double maxAccuracyCreditMeters) {
        if (reported == null || !reported.isPresent()) {
            return new Result.Missing();
        }
        double distance = distanceMeters(latitude, longitude, reported.latitude(), reported.longitude());
        double credit = accuracyCredit(reported.accuracyMeters(), maxAccuracyCreditMeters);
        return distance - credit <= radiusMeters ? new Result.Inside(distance) : new Result.Outside(distance);
    }

    static double accuracyCredit(Double accuracy, double cap) {
        if (accuracy == null || !Double.isFinite(accuracy) || accuracy < 0) {
            return 0;
        }
        return Math.min(accuracy, Math.max(0, cap));
    }

    /** Great-circle distance by the haversine formula; good to well under a metre at classroom scale. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        // Spelled out (not isValidCoordinate) so the bounds visibly guard the arithmetic below; NaN fails too.
        if (!(lat1 >= -90 && lat1 <= 90 && lat2 >= -90 && lat2 <= 90
                && lng1 >= -180 && lng1 <= 180 && lng2 >= -180 && lng2 <= 180)) {
            throw new IllegalArgumentException("Coordinates out of range");
        }
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    public static boolean isValidCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
    }
}
