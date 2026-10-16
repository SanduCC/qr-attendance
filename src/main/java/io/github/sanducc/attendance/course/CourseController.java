package io.github.sanducc.attendance.course;

import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.SessionService;
import io.github.sanducc.attendance.session.SessionService.CourseSummary;
import io.github.sanducc.attendance.session.SessionService.SessionSummary;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** The teacher's home (course cards with one-click "Start attendance") and each course's session history. */
@Controller
@RequestMapping("/teacher/courses")
public class CourseController {

    static final String COURSE_CREATED = "Course created";

    private final TeacherService teachers;
    private final CourseService courses;
    private final SessionService sessions;
    private final DisplayTime displayTime;
    private final AttendanceProperties properties;
    private final Clock clock;

    public CourseController(TeacherService teachers, CourseService courses, SessionService sessions,
                            DisplayTime displayTime, AttendanceProperties properties, Clock clock) {
        this.teachers = teachers;
        this.courses = courses;
        this.sessions = sessions;
        this.displayTime = displayTime;
        this.properties = properties;
        this.clock = clock;
    }

    /** One session, formatted for the page. {@code expectedHeadcount} is null when none was set. */
    public record SessionRow(UUID id, String day, String opensAt, String closesAt, boolean open, long checkedIn,
                             Integer expectedHeadcount) {}

    /** One course card. {@code lastSession} is null when the course has no sessions yet. */
    public record CourseCard(long id, String name, String code, SessionRow lastSession,
                             Integer nextExpectedHeadcount) {}

    @GetMapping
    public String list(OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        return coursesPage(teacherId, new CourseForm(), false, model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("courseForm") CourseForm form, BindingResult binding,
                         OAuth2AuthenticationToken authentication, Model model, RedirectAttributes redirect) {
        long teacherId = teachers.requireTeacherId(authentication);
        if (binding.hasErrors()) {
            return coursesPage(teacherId, form, true, model);
        }
        Course course = courses.createCourse(teacherId, form.getName(), form.getCode());
        redirect.addFlashAttribute("message", COURSE_CREATED);
        redirect.addFlashAttribute("createdCourseId", course.getId());
        return "redirect:/teacher/courses#course-" + course.getId();
    }

    @GetMapping("/{courseId}")
    public String detail(@PathVariable long courseId, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        return coursePage(teacherId, courseId, null, model);
    }

    /** "Start attendance": works with no fields at all (one click) or with the optional duration / headcount. */
    @PostMapping("/{courseId}/sessions")
    public String openSession(@PathVariable long courseId, @Valid @ModelAttribute("openForm") OpenSessionForm form,
                              BindingResult binding, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        long maxMinutes = maxMinutes();
        if (form.getDurationMinutes() != null && form.getDurationMinutes() > maxMinutes) {
            binding.rejectValue("durationMinutes", "max", "At most " + maxMinutes + " minutes");
        }
        if (binding.hasErrors()) {
            return coursePage(teacherId, courseId, form, model);
        }
        AttendanceSession session = sessions.startSession(teacherId, courseId, form.getDurationMinutes(),
                form.getExpectedHeadcount(), form.isHeadcountExplicit());
        return "redirect:/sessions/" + session.getId() + "/display";
    }

    private String coursesPage(long teacherId, CourseForm form, boolean formHasErrors, Model model) {
        Instant now = clock.instant();
        List<CourseCard> cards = sessions.listCourseSummaries(teacherId).stream()
                .map(summary -> card(summary, now))
                .toList();
        model.addAttribute("courses", cards);
        model.addAttribute("courseForm", form);
        model.addAttribute("showNewCourse", formHasErrors || cards.isEmpty());
        model.addAttribute("defaultDurationMinutes", defaultMinutes());
        model.addAttribute("maxDurationMinutes", maxMinutes());
        return "teacher/courses";
    }

    /** {@code invalidForm} is the rejected options form to show again with its errors, or null. */
    private String coursePage(long teacherId, long courseId, OpenSessionForm invalidForm, Model model) {
        Course course = courses.getOwnedCourse(teacherId, courseId);
        Instant now = clock.instant();
        List<SessionRow> rows = sessions.listSessionSummaries(teacherId, courseId).stream()
                .map(s -> row(s, now))
                .toList();
        // Newest first, so the first row's headcount is what a one-click start would reuse.
        Integer lastExpected = rows.isEmpty() ? null : rows.getFirst().expectedHeadcount();
        model.addAttribute("course", course);
        model.addAttribute("sessions", rows);
        model.addAttribute("lastExpectedHeadcount", lastExpected);
        model.addAttribute("openForm", invalidForm != null ? invalidForm
                : new OpenSessionForm(defaultMinutes(), lastExpected));
        model.addAttribute("showOptions", invalidForm != null);
        model.addAttribute("defaultDurationMinutes", defaultMinutes());
        model.addAttribute("maxDurationMinutes", maxMinutes());
        return "teacher/course";
    }

    private CourseCard card(CourseSummary summary, Instant now) {
        Course c = summary.course();
        SessionRow last = summary.lastSession() == null ? null : row(summary.lastSession(), now);
        return new CourseCard(c.getId(), c.getName(), c.getCode(), last, summary.lastExpectedHeadcount());
    }

    private SessionRow row(SessionSummary summary, Instant now) {
        AttendanceSession s = summary.session();
        return new SessionRow(s.getId(), displayTime.day(s.getOpenedAt()), displayTime.time(s.getOpenedAt()),
                displayTime.time(s.getClosesAt()), s.isOpenAt(now), summary.checkedIn(), s.getExpectedHeadcount());
    }

    private int defaultMinutes() {
        return (int) properties.session().defaultDuration().toMinutes();
    }

    private long maxMinutes() {
        return properties.session().maxDuration().toMinutes();
    }
}
