package io.github.sanducc.attendance.stats;

import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.course.TeacherService;
import java.util.List;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** The per-student stats page of a course (#8); its Excel and CSV exports are in {@code ExportController}. */
@Controller
public class CourseStatsController {

    private final TeacherService teachers;
    private final CourseStatsService stats;
    private final DisplayTime displayTime;

    public CourseStatsController(TeacherService teachers, CourseStatsService stats, DisplayTime displayTime) {
        this.teachers = teachers;
        this.stats = stats;
        this.displayTime = displayTime;
    }

    /** One table row, formatted for the page; {@code email} is null for a typed name. */
    public record StatsRow(String lastName, String firstName, String email, long attended, int percent,
                           String lastAttended, boolean typedName) {}

    @GetMapping("/teacher/courses/{courseId}/stats")
    public String stats(@PathVariable long courseId, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        CourseStats courseStats = stats.courseStats(teacherId, courseId);
        long total = courseStats.totalSessions();
        List<StatsRow> rows = courseStats.attendees().stream()
                .map(a -> new StatsRow(a.lastName(), a.firstName(), a.email(), a.attended(), a.percentOf(total),
                        displayTime.day(a.lastAttendedSessionAt()), a.typedName()))
                .toList();
        model.addAttribute("course", courseStats.course());
        model.addAttribute("totalSessions", total);
        model.addAttribute("sessionsWithoutEntries", courseStats.sessionsWithoutEntries());
        model.addAttribute("firstDay", courseStats.firstSessionAt() == null ? null
                : displayTime.day(courseStats.firstSessionAt()));
        model.addAttribute("lastDay", courseStats.lastSessionAt() == null ? null
                : displayTime.day(courseStats.lastSessionAt()));
        model.addAttribute("typedNames", courseStats.typedNameCount());
        model.addAttribute("rows", rows);
        return "teacher/stats";
    }
}
