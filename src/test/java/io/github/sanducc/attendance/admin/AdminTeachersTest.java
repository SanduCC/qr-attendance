package io.github.sanducc.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.sanducc.attendance.config.AppOidcUserService;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.course.TeacherService;
import io.github.sanducc.attendance.course.TeacherService.LoginRoles;
import io.github.sanducc.attendance.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** #24: only admins (from config) decide who is a teacher; nobody gets the role by signing in. */
class AdminTeachersTest extends IntegrationTestSupport {

    /** Matches attendance.admin-emails in application-test.yml (different case on purpose). */
    private static final String ADMIN = "head.admin@university.example.ro";
    private static final String NEW_TEACHER = "new.teacher@university.example.ro";

    @Autowired TeacherService teacherService;

    private RequestPostProcessor admin() {
        return login("sub-admin", ADMIN, "Head", "Admin", AppOidcUserService.ROLE_STUDENT,
                AppOidcUserService.ROLE_TEACHER, AppOidcUserService.ROLE_ADMIN);
    }

    // ------------------------------------------------------------------ roles at sign-in

    @Test
    void adminEmailFromConfigGetsAdminAndTeacherAndATeacherRow() {
        LoginRoles roles = teacherService.rolesForLogin("Head.Admin@University.example.ro", true, "Head Admin");

        assertThat(roles).isEqualTo(new LoginRoles(true, true));
        Teacher row = teacherRepository.findByEmail(ADMIN).orElseThrow();
        assertThat(row.isActive()).isTrue();
        assertThat(row.getName()).isEqualTo("Head Admin");
        assertThat(row.getLastLoginAt()).isEqualTo(clock.instant());
    }

    @Test
    void signingInNeverMakesSomeoneATeacherOnItsOwn() {
        assertThat(teacherService.rolesForLogin("someone@gmail.com", true, "Someone"))
                .isEqualTo(new LoginRoles(false, false));
        assertThat(teacherRepository.findByEmail("someone@gmail.com")).isEmpty();
    }

    @Test
    void unverifiedEmailGetsNoTeacherOrAdminRole() {
        teacherService.addTeacher(ADMIN, NEW_TEACHER);

        assertThat(teacherService.rolesForLogin(ADMIN, false, "x")).isEqualTo(new LoginRoles(false, false));
        assertThat(teacherService.rolesForLogin(NEW_TEACHER, false, "x")).isEqualTo(new LoginRoles(false, false));
    }

    @Test
    void addedTeacherGetsTheTeacherRoleAtSignInAndDeactivatedOneDoesNot() {
        Teacher added = teacherService.addTeacher(ADMIN, "  New.Teacher@University.example.ro ");
        assertThat(added.getEmail()).isEqualTo(NEW_TEACHER);
        assertThat(added.getAddedBy()).isEqualTo(ADMIN);
        assertThat(added.getName()).isNull();

        assertThat(teacherService.rolesForLogin(NEW_TEACHER, true, "Ana Popescu"))
                .isEqualTo(new LoginRoles(true, false));
        assertThat(teacherRepository.findByEmail(NEW_TEACHER).orElseThrow().getName()).isEqualTo("Ana Popescu");

        teacherService.setActive(ADMIN, added.getId(), false);
        assertThat(teacherService.rolesForLogin(NEW_TEACHER, true, "Ana Popescu"))
                .isEqualTo(new LoginRoles(false, false));
    }

    // ------------------------------------------------------------------ who can reach /admin

    @Test
    void onlyAdminsReachTheTeachersPage() throws Exception {
        mvc.perform(get("/admin/teachers"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/admin/teachers").with(studentLogin("student-1", "Ana", "Pop")))
                .andExpect(status().isForbidden());
        createTeacher("prof.a@university.example.ro");
        mvc.perform(get("/admin/teachers").with(teacherLogin("prof.a@university.example.ro")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/admin/teachers").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/teachers"));
    }

    @Test
    void aForgedAdminAuthorityWithoutAnAdminEmailIsRefusedByTheService() throws Exception {
        RequestPostProcessor forged = login("sub-x", "not.admin@university.example.ro", "Not", "Admin",
                AppOidcUserService.ROLE_STUDENT, AppOidcUserService.ROLE_ADMIN);

        mvc.perform(get("/admin/teachers").with(forged)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/teachers").with(csrf()).with(forged).param("email", NEW_TEACHER))
                .andExpect(status().isForbidden());
        assertThat(teacherRepository.findByEmail(NEW_TEACHER)).isEmpty();
    }

    // ------------------------------------------------------------------ managing teachers

    @Test
    void adminAddsATeacher() throws Exception {
        mvc.perform(post("/admin/teachers").with(csrf()).with(admin()).param("email", "New.Teacher@university.example.ro"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/teachers"))
                .andExpect(flash().attributeExists("message"));

        Teacher row = teacherRepository.findByEmail(NEW_TEACHER).orElseThrow();
        assertThat(row.isActive()).isTrue();
        assertThat(row.getAddedBy()).isEqualTo(ADMIN);
    }

    @Test
    void addingRequiresCsrfAndAValidEmail() throws Exception {
        mvc.perform(post("/admin/teachers").with(admin()).param("email", NEW_TEACHER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/teachers").with(csrf()).with(admin()).param("email", "not-an-email"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/teachers"))
                .andExpect(model().attributeHasFieldErrors("inviteForm", "email"));
        assertThat(teacherRepository.count()).isZero();
    }

    @Test
    void deactivationRevokesAccessImmediatelyButKeepsTheCourses() throws Exception {
        Teacher teacher = teacherService.addTeacher(ADMIN, NEW_TEACHER);
        long courseId = courseService.createCourse(teacher.getId(), "Algoritmi", "CS101").getId();
        RequestPostProcessor signedInTeacher = teacherLogin(NEW_TEACHER);  // role granted at an earlier sign-in
        mvc.perform(get("/teacher/courses").with(signedInTeacher)).andExpect(status().isOk());

        mvc.perform(post("/admin/teachers/{id}/deactivate", teacher.getId()).with(csrf()).with(admin()))
                .andExpect(redirectedUrl("/admin/teachers"))
                .andExpect(flash().attributeExists("message"));

        mvc.perform(get("/teacher/courses").with(signedInTeacher)).andExpect(status().isForbidden());
        mvc.perform(get("/teacher/courses/{id}", courseId).with(signedInTeacher)).andExpect(status().isForbidden());
        assertThat(courseService.listCourses(teacher.getId())).hasSize(1);

        mvc.perform(post("/admin/teachers/{id}/activate", teacher.getId()).with(csrf()).with(admin()))
                .andExpect(redirectedUrl("/admin/teachers"));
        mvc.perform(get("/teacher/courses").with(signedInTeacher)).andExpect(status().isOk());
    }

    @Test
    void reAddingADeactivatedTeacherReactivatesTheSameRow() {
        Teacher teacher = teacherService.addTeacher(ADMIN, NEW_TEACHER);
        teacherService.setActive(ADMIN, teacher.getId(), false);

        Teacher again = teacherService.addTeacher(ADMIN, NEW_TEACHER);

        assertThat(again.getId()).isEqualTo(teacher.getId());
        assertThat(teacherRepository.findByEmail(NEW_TEACHER).orElseThrow().isActive()).isTrue();
        assertThat(teacherRepository.count()).isEqualTo(1);
    }

    @Test
    void adminsCannotBeDeactivatedFromTheUi() throws Exception {
        teacherService.rolesForLogin(ADMIN, true, "Head Admin");
        Teacher adminRow = teacherRepository.findByEmail(ADMIN).orElseThrow();

        mvc.perform(post("/admin/teachers/{id}/deactivate", adminRow.getId()).with(csrf()).with(admin()))
                .andExpect(redirectedUrl("/admin/teachers"))
                .andExpect(flash().attributeExists("error"));

        assertThat(teacherRepository.findByEmail(ADMIN).orElseThrow().isActive()).isTrue();
    }

    @Test
    void unknownTeacherIdIs404() throws Exception {
        mvc.perform(post("/admin/teachers/{id}/deactivate", 999_999).with(csrf()).with(admin()))
                .andExpect(status().isNotFound());
    }
}
