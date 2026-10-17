package io.github.sanducc.attendance.common;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /robots.txt}: nothing here is meant for search engines (scan links are short-lived and private). Public,
 * stateless and cacheable; a controller rather than a static file so it can carry its own {@code Cache-Control}
 * (Spring Security sends {@code no-store} on everything that doesn't set one).
 */
@RestController
public class RobotsController {

    static final String ROBOTS_TXT = "User-agent: *\nDisallow: /\n";

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(ROBOTS_TXT);
    }
}
