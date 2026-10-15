package io.github.sanducc.attendance.course;

import io.github.sanducc.attendance.checkin.AttendanceRecordService;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.SessionService;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/teacher/courses")
public class CourseController {

    private final TeacherService teachers;
    private final CourseService courses;
    private final SessionService sessions;
    private final AttendanceRecordService records;
    private final DisplayTime displayTime;
    private final AttendanceProperties properties;
    private final Clock clock;

    public CourseController(TeacherService teachers, CourseService courses, SessionService sessions,
                            AttendanceRecordService records, DisplayTime displayTime,
                            AttendanceProperties properties, Clock clock) {
        this.teachers = teachers;
        this.courses = courses;
        this.sessions = sessions;
        this.records = records;
        this.displayTime = displayTime;
        this.properties = properties;
        this.clock = clock;
    }

    public record SessionRow(UUID id, String openedAt, String closesAt, boolean open, long checkedIn) {}

    @GetMapping
    public String list(OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        return coursesPage(teacherId, new CourseForm(), model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("courseForm") CourseForm form, BindingResult binding,
                         OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        if (binding.hasErrors()) {
            return coursesPage(teacherId, form, model);
        }
        Course course = courses.createCourse(teacherId, form.getName(), form.getCode());
        return "redirect:/teacher/courses/" + course.getId();
    }

    @GetMapping("/{courseId}")
    public String detail(@PathVariable long courseId, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        OpenSessionForm form = new OpenSessionForm((int) properties.session().defaultDuration().toMinutes());
        return coursePage(teacherId, courseId, form, model);
    }

    @PostMapping("/{courseId}/sessions")
    public String openSession(@PathVariable long courseId, @Valid @ModelAttribute("openForm") OpenSessionForm form,
                              BindingResult binding, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        long maxMinutes = properties.session().maxDuration().toMinutes();
        if (form.getDurationMinutes() != null && form.getDurationMinutes() > maxMinutes) {
            binding.rejectValue("durationMinutes", "max", "At most " + maxMinutes + " minutes");
        }
        if (binding.hasErrors()) {
            return coursePage(teacherId, courseId, form, model);
        }
        AttendanceSession session = sessions.openSession(teacherId, courseId, form.getDurationMinutes(),
                form.getExpectedHeadcount());
        return "redirect:/sessions/" + session.getId() + "/display";
    }

    private String coursesPage(long teacherId, CourseForm form, Model model) {
        model.addAttribute("courses", courses.listCourses(teacherId));
        model.addAttribute("courseForm", form);
        return "teacher/courses";
    }

    private String coursePage(long teacherId, long courseId, OpenSessionForm form, Model model) {
        Course course = courses.getOwnedCourse(teacherId, courseId);
        List<SessionRow> rows = sessions.listSessions(teacherId, courseId).stream()
                .map(s -> new SessionRow(s.getId(), displayTime.dateTime(s.getOpenedAt()),
                        displayTime.time(s.getClosesAt()), s.isOpenAt(clock.instant()),
                        records.countRecords(teacherId, s.getId())))
                .toList();
        model.addAttribute("course", course);
        model.addAttribute("sessions", rows);
        model.addAttribute("openForm", form);
        model.addAttribute("maxDurationMinutes", properties.session().maxDuration().toMinutes());
        return "teacher/course";
    }
}
