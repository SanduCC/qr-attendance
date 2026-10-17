package io.github.sanducc.attendance.course;

import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.config.AttendanceProperties.GeofenceSettings;
import io.github.sanducc.attendance.course.WorkspaceDomains.InvalidDomainsException;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.Geofence;
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
    static final String COURSE_SAVED = "Course saved";
    static final String LOCATION_MISSING = "We couldn't get this device's location. Allow location access and try "
            + "again, or untick \"Require location\".";

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

    /**
     * One session, formatted for the page. {@code expectedHeadcount} is null when none was set,
     * {@code geofenceRadiusM} when the session has no location check.
     */
    public record SessionRow(UUID id, String day, String opensAt, String closesAt, boolean open, long checkedIn,
                             Integer expectedHeadcount, Integer geofenceRadiusM) {}

    /**
     * One course card. {@code lastSession} is null when the course has no sessions yet; {@code allowedDomains} is
     * e.g. "@university.ro", empty without a domain lock.
     */
    public record CourseCard(long id, String name, String code, SessionRow lastSession,
                             Integer nextExpectedHeadcount, String allowedDomains) {}

    @GetMapping
    public String list(OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        return coursesPage(teacherId, new CourseForm(), false, model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("courseForm") CourseForm form, BindingResult binding,
                         OAuth2AuthenticationToken authentication, Model model, RedirectAttributes redirect) {
        long teacherId = teachers.requireTeacherId(authentication);
        List<String> domains = parseDomains(form, binding);
        if (binding.hasErrors()) {
            return coursesPage(teacherId, form, true, model);
        }
        Course course = courses.createCourse(teacherId, form.getName(), form.getCode(), domains);
        redirect.addFlashAttribute("message", COURSE_CREATED);
        redirect.addFlashAttribute("createdCourseId", course.getId());
        return "redirect:/teacher/courses#course-" + course.getId();
    }

    @GetMapping("/{courseId}")
    public String detail(@PathVariable long courseId, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        return coursePage(teacherId, courseId, null, null, model);
    }

    /** Edit name, code and the Workspace domain lock (rule 7). Someone else's course is a 404. */
    @PostMapping("/{courseId}/edit")
    public String edit(@PathVariable long courseId, @Valid @ModelAttribute("courseForm") CourseForm form,
                       BindingResult binding, OAuth2AuthenticationToken authentication, Model model,
                       RedirectAttributes redirect) {
        long teacherId = teachers.requireTeacherId(authentication);
        List<String> domains = parseDomains(form, binding);
        if (binding.hasErrors()) {
            return coursePage(teacherId, courseId, null, form, model);
        }
        courses.updateCourse(teacherId, courseId, form.getName(), form.getCode(), domains);
        redirect.addFlashAttribute("message", COURSE_SAVED);
        return "redirect:/teacher/courses/" + courseId;
    }

    /**
     * "Start attendance": works with no fields at all (one click) or with the optional duration / headcount /
     * location check (rule 6; the classroom position comes from this device, captured by start-options.js).
     */
    @PostMapping("/{courseId}/sessions")
    public String openSession(@PathVariable long courseId, @Valid @ModelAttribute("openForm") OpenSessionForm form,
                              BindingResult binding, OAuth2AuthenticationToken authentication, Model model) {
        long teacherId = teachers.requireTeacherId(authentication);
        long maxMinutes = maxMinutes();
        if (form.getDurationMinutes() != null && form.getDurationMinutes() > maxMinutes) {
            binding.rejectValue("durationMinutes", "max", "At most " + maxMinutes + " minutes");
        }
        Geofence geofence = geofence(form, binding);
        if (binding.hasErrors()) {
            return coursePage(teacherId, courseId, form, null, model);
        }
        AttendanceSession session = sessions.startSession(teacherId, courseId, form.getDurationMinutes(),
                form.getExpectedHeadcount(), form.isHeadcountExplicit(), geofence);
        return "redirect:/sessions/" + session.getId() + "/display";
    }

    private static List<String> parseDomains(CourseForm form, BindingResult binding) {
        if (binding.hasFieldErrors("allowedDomains")) {
            return List.of();
        }
        try {
            return WorkspaceDomains.parse(form.getAllowedDomains());
        } catch (InvalidDomainsException e) {
            binding.rejectValue("allowedDomains", "invalid", e.getMessage());
            return List.of();
        }
    }

    /** The session's location check, or null when "Require location" isn't ticked or the input is invalid. */
    private Geofence geofence(OpenSessionForm form, BindingResult binding) {
        if (!form.isRequireLocation()) {
            return null;
        }
        GeofenceSettings bounds = properties.geofence();
        int radius = form.getGeofenceRadiusM() == null ? bounds.defaultRadiusM() : form.getGeofenceRadiusM();
        boolean ok = true;
        if (radius < bounds.minRadiusM() || radius > bounds.maxRadiusM()) {
            binding.rejectValue("geofenceRadiusM", "range",
                    "Between " + bounds.minRadiusM() + " and " + bounds.maxRadiusM() + " m");
            ok = false;
        }
        Double lat = form.getLatitude();
        Double lng = form.getLongitude();
        if (lat == null || lng == null || !Geofence.isValidCoordinate(lat, lng)) {
            binding.rejectValue("requireLocation", "location", LOCATION_MISSING);
            ok = false;
        }
        return ok ? new Geofence(lat, lng, radius) : null;
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
        addGeofenceBounds(model);
        return "teacher/courses";
    }

    /**
     * {@code invalidOpenForm} / {@code invalidCourseForm} are the rejected options or edit form to show again with
     * their errors, or null.
     */
    private String coursePage(long teacherId, long courseId, OpenSessionForm invalidOpenForm,
                              CourseForm invalidCourseForm, Model model) {
        Course course = courses.getOwnedCourse(teacherId, courseId);
        Instant now = clock.instant();
        List<SessionRow> rows = sessions.listSessionSummaries(teacherId, courseId).stream()
                .map(s -> row(s, now))
                .toList();
        // Newest first, so the first row's headcount is what a one-click start would reuse.
        Integer lastExpected = rows.isEmpty() ? null : rows.getFirst().expectedHeadcount();
        OpenSessionForm openForm = invalidOpenForm;
        if (openForm == null) {
            openForm = new OpenSessionForm(defaultMinutes(), lastExpected);
            openForm.setGeofenceRadiusM(properties.geofence().defaultRadiusM());
        }
        model.addAttribute("course", course);
        model.addAttribute("allowedDomains", WorkspaceDomains.describe(course.getAllowedDomains()));
        model.addAttribute("sessions", rows);
        model.addAttribute("lastExpectedHeadcount", lastExpected);
        model.addAttribute("openForm", openForm);
        model.addAttribute("showOptions", invalidOpenForm != null);
        model.addAttribute("courseForm", invalidCourseForm != null ? invalidCourseForm : CourseForm.of(course));
        model.addAttribute("showEdit", invalidCourseForm != null);
        model.addAttribute("defaultDurationMinutes", defaultMinutes());
        model.addAttribute("maxDurationMinutes", maxMinutes());
        addGeofenceBounds(model);
        return "teacher/course";
    }

    private void addGeofenceBounds(Model model) {
        GeofenceSettings bounds = properties.geofence();
        model.addAttribute("geofenceDefaultRadiusM", bounds.defaultRadiusM());
        model.addAttribute("geofenceMinRadiusM", bounds.minRadiusM());
        model.addAttribute("geofenceMaxRadiusM", bounds.maxRadiusM());
    }

    private CourseCard card(CourseSummary summary, Instant now) {
        Course c = summary.course();
        SessionRow last = summary.lastSession() == null ? null : row(summary.lastSession(), now);
        return new CourseCard(c.getId(), c.getName(), c.getCode(), last, summary.lastExpectedHeadcount(),
                WorkspaceDomains.describe(c.getAllowedDomains()));
    }

    private SessionRow row(SessionSummary summary, Instant now) {
        AttendanceSession s = summary.session();
        Geofence geofence = s.getGeofence();
        return new SessionRow(s.getId(), displayTime.day(s.getOpenedAt()), displayTime.time(s.getOpenedAt()),
                displayTime.time(s.getClosesAt()), s.isOpenAt(now), summary.checkedIn(), s.getExpectedHeadcount(),
                geofence == null ? null : geofence.radiusMeters());
    }

    private int defaultMinutes() {
        return (int) properties.session().defaultDuration().toMinutes();
    }

    private long maxMinutes() {
        return properties.session().maxDuration().toMinutes();
    }
}
