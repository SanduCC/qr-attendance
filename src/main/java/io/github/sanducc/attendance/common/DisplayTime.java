package io.github.sanducc.attendance.common;

import io.github.sanducc.attendance.config.AttendanceProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Times are stored in UTC and rendered in the configured zone (Europe/Bucharest). */
@Component
public class DisplayTime {

    private final DateTimeFormatter time;
    private final DateTimeFormatter timeWithSeconds;
    private final DateTimeFormatter dateTime;
    private final ZoneId zone;

    public DisplayTime(AttendanceProperties properties) {
        this.zone = properties.displayZone();
        this.time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone);
        this.timeWithSeconds = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone);
        this.dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
    }

    public String time(Instant instant) {
        return time.format(instant);
    }

    public String timeWithSeconds(Instant instant) {
        return timeWithSeconds.format(instant);
    }

    public String dateTime(Instant instant) {
        return dateTime.format(instant);
    }

    public ZoneId zone() {
        return zone;
    }
}
