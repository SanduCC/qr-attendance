package io.github.sanducc.attendance.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.common.NotFoundException;
import io.github.sanducc.attendance.session.AttendanceSession;
import io.github.sanducc.attendance.session.Geofence;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The teacher's side of rules 6 and 7: setting the domain lock and starting a session with a location check. */
class CourseRulesTest extends IntegrationTestSupport {

    private static final String PROF_A = "prof.a@university.example.ro";
    private static final String PROF_B = "prof.b@university.example.ro";
    private static final String LAT = "44.4386";
    private static final String LNG = "26.0494";

    private RequestPostProcessor profA() {
        return teacherLogin(PROF_A);
    }

    private long courseOfProfA() {
        long teacherId = createTeacher(PROF_A).getId();
        return courseService.createCourse(teacherId, "Algoritmi", "CS101").getId();
    }

    private Course reload(long courseId) {
        return courseService.getOwnedCourse(teacherRepository.findByEmail(PROF_A).orElseThrow().getId(), courseId);
    }

    private MockHttpServletRequestBuilder edit(long courseId, String name, String code, String domains) {
        return post("/teacher/courses/{id}/edit", courseId).with(csrf())
                .param("name", name).param("code", code).param("allowedDomains", domains);
    }

    private MockHttpServletRequestBuilder startWithOptions(long courseId) {
        return post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(profA())
                .param("headcountExplicit", "true").param("durationMinutes", "15").param("expectedHeadcount", "");
    }

    private AttendanceSession sessionFrom(MvcResult result) {
        UUID id = UUID.fromString(result.getResponse().getRedirectedUrl().split("/")[2]);
        return sessionRepository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ rule 7: domain lock

    @Test
    void theDomainLockIsSetWhenCreatingTheCourse() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();

        mvc.perform(post("/teacher/courses").with(csrf()).with(profA())
                        .param("name", "Algoritmi").param("code", "")
                        .param("allowedDomains", " @University.example.ro, stud.university.example.ro "))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "Course created"));
        mvc.perform(post("/teacher/courses").with(csrf()).with(profA()).param("name", "Baze de date"))
                .andExpect(status().is3xxRedirection());

        List<Course> courses = courseService.listCourses(teacherId);
        assertThat(courses).extracting(Course::getName).containsExactly("Algoritmi", "Baze de date");
        assertThat(courses.get(0).getAllowedDomains())
                .containsExactly("university.example.ro", "stud.university.example.ro");
        assertThat(courses.get(1).getAllowedDomains()).as("defaults to no restriction").isEmpty();
        assertThat(courses.get(1).isDomainLocked()).isFalse();

        MvcResult page = mvc.perform(get("/teacher/courses").with(profA())).andExpect(status().isOk()).andReturn();
        @SuppressWarnings("unchecked")
        List<CourseController.CourseCard> cards =
                (List<CourseController.CourseCard>) page.getModelAndView().getModel().get("courses");
        assertThat(cards).extracting(CourseController.CourseCard::allowedDomains)
                .containsExactly("@university.example.ro or @stud.university.example.ro", "");
        assertThat(page.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("@university.example.ro or @stud.university.example.ro only", "allowedDomains");
    }

    @Test
    void invalidDomainsAreRejectedWithTheFormShownAgain() throws Exception {
        long teacherId = createTeacher(PROF_A).getId();

        for (String bad : new String[] {"gmail.com", "not a domain", "university", "x".repeat(501)}) {
            mvc.perform(post("/teacher/courses").with(csrf()).with(profA())
                            .param("name", "Algoritmi").param("allowedDomains", bad))
                    .andExpect(status().isOk())
                    .andExpect(view().name("teacher/courses"))
                    .andExpect(model().attributeHasFieldErrors("courseForm", "allowedDomains"))
                    .andExpect(model().attribute("showNewCourse", true));
        }
        assertThat(courseService.listCourses(teacherId)).isEmpty();
    }

    @Test
    void theTeacherCanLockChangeAndLiftTheDomainOfACourse() throws Exception {
        long courseId = courseOfProfA();

        mvc.perform(get("/teacher/courses/{id}", courseId).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("showEdit", false))
                .andExpect(model().attribute("courseForm", allOf(hasProperty("name", is("Algoritmi")),
                        hasProperty("code", is("CS101")), hasProperty("allowedDomains", is("")))));

        mvc.perform(edit(courseId, " Algoritmi 2 ", "CS102", "University.example.ro").with(profA()))
                .andExpect(redirectedUrl("/teacher/courses/" + courseId))
                .andExpect(flash().attribute("message", "Course saved"));
        Course locked = reload(courseId);
        assertThat(locked.getName()).isEqualTo("Algoritmi 2");
        assertThat(locked.getCode()).isEqualTo("CS102");
        assertThat(locked.getAllowedDomains()).containsExactly("university.example.ro");

        mvc.perform(get("/teacher/courses/{id}", courseId).with(profA()))
                .andExpect(model().attribute("allowedDomains", "@university.example.ro"))
                .andExpect(model().attribute("courseForm", hasProperty("allowedDomains", is("university.example.ro"))))
                .andExpect(content().string(containsString("@university.example.ro only")));

        mvc.perform(edit(courseId, "Algoritmi 2", "", "").with(profA()))
                .andExpect(redirectedUrl("/teacher/courses/" + courseId));
        Course lifted = reload(courseId);
        assertThat(lifted.getAllowedDomains()).isEmpty();
        assertThat(lifted.getCode()).isNull();
        Integer stored = jdbc.queryForObject("select count(*) from course where id = ? and allowed_domains is null",
                Integer.class, courseId);
        assertThat(stored).as("no lock is stored as null").isEqualTo(1);
    }

    @Test
    void anInvalidEditIsShownAgainAndChangesNothing() throws Exception {
        long courseId = courseOfProfA();

        mvc.perform(edit(courseId, "Algoritmi", "CS101", "googlemail.com").with(profA()))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/course"))
                .andExpect(model().attributeHasFieldErrors("courseForm", "allowedDomains"))
                .andExpect(model().attribute("showEdit", true));
        mvc.perform(edit(courseId, " ", "CS101", "university.example.ro").with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("courseForm", "name"));

        assertThat(reload(courseId).getAllowedDomains()).isEmpty();
        assertThat(reload(courseId).getName()).isEqualTo("Algoritmi");
    }

    @Test
    void onlyTheCoursesTeacherCanSetOrLiftTheLock() throws Exception {
        long courseId = courseOfProfA();
        courseService.updateCourse(teacherRepository.findByEmail(PROF_A).orElseThrow().getId(), courseId,
                "Algoritmi", "CS101", List.of("university.example.ro"));
        long profB = createTeacher(PROF_B).getId();

        mvc.perform(edit(courseId, "Mine now", "X", "").with(teacherLogin(PROF_B)))
                .andExpect(status().isNotFound());
        mvc.perform(edit(courseId, " ", "X", "gmail.com").with(teacherLogin(PROF_B)))
                .andExpect(status().isNotFound());
        mvc.perform(edit(courseId, "Algoritmi", "CS101", "").with(studentLogin("sub-ana", "Ana", "Popescu")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/teacher/courses/{id}/edit", courseId).with(profA()).param("name", "No CSRF"))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> courseService.updateCourse(profB, courseId, "Mine", null, List.of()))
                .isInstanceOf(NotFoundException.class);

        Course course = reload(courseId);
        assertThat(course.getName()).isEqualTo("Algoritmi");
        assertThat(course.getAllowedDomains()).containsExactly("university.example.ro");
    }

    // ------------------------------------------------------------------ rule 6: geofence

    @Test
    void startingWithRequireLocationStoresTheClassroom() throws Exception {
        long courseId = courseOfProfA();

        AttendanceSession custom = sessionFrom(mvc.perform(startWithOptions(courseId)
                        .param("requireLocation", "true").param("geofenceRadiusM", "150")
                        .param("latitude", LAT).param("longitude", LNG))
                .andExpect(redirectedUrlPattern("/sessions/*/display"))
                .andReturn());
        assertThat(custom.getGeofence()).isEqualTo(new Geofence(44.4386, 26.0494, 150));

        AttendanceSession defaultRadius = sessionFrom(mvc.perform(startWithOptions(courseId)
                        .param("requireLocation", "true").param("geofenceRadiusM", "")
                        .param("latitude", LAT).param("longitude", LNG))
                .andExpect(redirectedUrlPattern("/sessions/*/display"))
                .andReturn());
        assertThat(defaultRadius.getGeofence().radiusMeters()).isEqualTo(100);

        mvc.perform(get("/sessions/{id}/display", custom.getId()).with(profA()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("geofenceRadiusM", 150))
                .andExpect(content().string(containsString("Location check on: within 150 m")));
        mvc.perform(get("/teacher/courses/{id}", courseId).with(profA()))
                .andExpect(content().string(containsString("Location 150 m")));
    }

    @Test
    void theLocationCheckIsOffUnlessTicked() throws Exception {
        long courseId = courseOfProfA();

        // One click: no geofence.
        AttendanceSession oneClick = sessionFrom(mvc.perform(post("/teacher/courses/{id}/sessions", courseId)
                        .with(csrf()).with(profA()))
                .andExpect(redirectedUrlPattern("/sessions/*/display")).andReturn());
        assertThat(oneClick.getGeofence()).isNull();
        // Coordinates without the checkbox are ignored.
        AttendanceSession unticked = sessionFrom(mvc.perform(startWithOptions(courseId)
                        .param("latitude", LAT).param("longitude", LNG).param("geofenceRadiusM", "100"))
                .andExpect(redirectedUrlPattern("/sessions/*/display")).andReturn());
        assertThat(unticked.getGeofence()).isNull();

        mvc.perform(get("/sessions/{id}/display", oneClick.getId()).with(profA()))
                .andExpect(model().attribute("geofenceRadiusM", (Object) null));
        Integer withGeofence = jdbc.queryForObject("select count(*) from attendance_session where geofence_lat is not "
                + "null or geofence_lng is not null or geofence_radius_m is not null", Integer.class);
        assertThat(withGeofence).isZero();
    }

    @Test
    void requireLocationNeedsThisDevicesPositionAndARadiusWithinBounds() throws Exception {
        long courseId = courseOfProfA();

        // The browser couldn't (or wouldn't) give a position.
        mvc.perform(startWithOptions(courseId).param("requireLocation", "true").param("geofenceRadiusM", "100"))
                .andExpect(status().isOk())
                .andExpect(view().name("teacher/course"))
                .andExpect(model().attributeHasFieldErrors("openForm", "requireLocation"))
                .andExpect(model().attribute("showOptions", true));
        mvc.perform(startWithOptions(courseId).param("requireLocation", "true")
                        .param("latitude", "91").param("longitude", LNG))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("openForm", "requireLocation"));
        mvc.perform(startWithOptions(courseId).param("requireLocation", "true")
                        .param("latitude", "abc").param("longitude", LNG))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("openForm", "requireLocation"));
        for (String radius : new String[] {"24", "1001", "0", "-5"}) {
            mvc.perform(startWithOptions(courseId).param("requireLocation", "true").param("geofenceRadiusM", radius)
                            .param("latitude", LAT).param("longitude", LNG))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("openForm", "geofenceRadiusM"));
        }
        assertThat(sessionRepository.count()).isZero();

        // The bounds hold in the service too, not only in the form.
        long teacherId = teacherRepository.findByEmail(PROF_A).orElseThrow().getId();
        assertThatThrownBy(() -> sessionService.openSession(teacherId, courseId, 15, null,
                new Geofence(44.4386, 26.0494, 5000))).isInstanceOf(IllegalArgumentException.class);
        assertThat(sessionRepository.count()).isZero();
    }

    @Test
    void onlyTheCoursesTeacherCanStartALocationCheckedSession() throws Exception {
        long courseId = courseOfProfA();
        long profB = createTeacher(PROF_B).getId();

        mvc.perform(post("/teacher/courses/{id}/sessions", courseId).with(csrf()).with(teacherLogin(PROF_B))
                        .param("requireLocation", "true").param("latitude", LAT).param("longitude", LNG))
                .andExpect(status().isNotFound());
        assertThatThrownBy(() -> sessionService.startSession(profB, courseId, null, null, false,
                new Geofence(44.4386, 26.0494, 100))).isInstanceOf(NotFoundException.class);
        assertThat(sessionRepository.count()).isZero();
    }
}
