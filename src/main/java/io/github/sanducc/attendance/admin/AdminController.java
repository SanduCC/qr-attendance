package io.github.sanducc.attendance.admin;

import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.course.Teacher;
import io.github.sanducc.attendance.course.TeacherService;
import jakarta.validation.Valid;
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

/** Admins decide who is a teacher. The admin checks themselves live in {@link TeacherService}. */
@Controller
@RequestMapping("/admin/teachers")
public class AdminController {

    private final TeacherService teachers;
    private final DisplayTime displayTime;

    public AdminController(TeacherService teachers, DisplayTime displayTime) {
        this.teachers = teachers;
        this.displayTime = displayTime;
    }

    @GetMapping
    public String list(OAuth2AuthenticationToken authentication, Model model) {
        return page(teachers.requireAdminEmail(authentication), new TeacherInviteForm(), model);
    }

    @PostMapping
    public String add(@Valid @ModelAttribute("inviteForm") TeacherInviteForm form, BindingResult binding,
                      OAuth2AuthenticationToken authentication, Model model, RedirectAttributes redirect) {
        String admin = teachers.requireAdminEmail(authentication);
        if (binding.hasErrors()) {
            return page(admin, form, model);
        }
        Teacher teacher = teachers.addTeacher(admin, form.getEmail());
        redirect.addFlashAttribute("message", teacher.getEmail() + " can now sign in as a teacher.");
        return "redirect:/admin/teachers";
    }

    @PostMapping("/{teacherId}/deactivate")
    public String deactivate(@PathVariable long teacherId, OAuth2AuthenticationToken authentication,
                             RedirectAttributes redirect) {
        return setActive(teacherId, false, authentication, redirect);
    }

    @PostMapping("/{teacherId}/activate")
    public String activate(@PathVariable long teacherId, OAuth2AuthenticationToken authentication,
                           RedirectAttributes redirect) {
        return setActive(teacherId, true, authentication, redirect);
    }

    private String setActive(long teacherId, boolean active, OAuth2AuthenticationToken authentication,
                             RedirectAttributes redirect) {
        String admin = teachers.requireAdminEmail(authentication);
        try {
            Teacher teacher = teachers.setActive(admin, teacherId, active);
            redirect.addFlashAttribute("message", active
                    ? teacher.getEmail() + " is a teacher again."
                    : teacher.getEmail() + " can no longer use teacher pages. Their courses and attendance are kept.");
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/teachers";
    }

    private String page(String admin, TeacherInviteForm form, Model model) {
        model.addAttribute("teachers", teachers.listTeachers(admin));
        model.addAttribute("inviteForm", form);
        model.addAttribute("displayTime", displayTime);
        return "admin/teachers";
    }
}
