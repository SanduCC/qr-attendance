package io.github.sanducc.attendance.checkin;

import io.github.sanducc.attendance.checkin.CheckInService.CheckInOutcome;
import io.github.sanducc.attendance.checkin.CheckInService.ScanOutcome;
import io.github.sanducc.attendance.common.AuthenticatedUser;
import io.github.sanducc.attendance.common.ClientContext;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.common.InAppBrowser;
import io.github.sanducc.attendance.config.AttendanceProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Student flow: scan → sign in → confirm name → check in → done. */
@Controller
@RequestMapping("/a/{sessionId}")
public class ScanController {

    private static final String SCAN_AGAIN = "Scan the QR code on the screen again.";

    private final CheckInService checkIns;
    private final DisplayTime displayTime;
    private final AttendanceProperties properties;

    public ScanController(CheckInService checkIns, DisplayTime displayTime, AttendanceProperties properties) {
        this.checkIns = checkIns;
        this.displayTime = displayTime;
        this.properties = properties;
    }

    @GetMapping
    public String scan(@PathVariable UUID sessionId, @RequestParam(name = "t", required = false) String token,
                       Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                       Model model) {
        ScanOutcome outcome = checkIns.scan(sessionId, token, ClientContext.from(request));
        return switch (outcome) {
            case ScanOutcome.Accepted accepted -> {
                accepted.pending().storeIn(request.getSession(true));
                if (authentication instanceof OAuth2AuthenticationToken) {
                    yield "redirect:/a/" + sessionId + "/confirm";
                }
                String currentUrl = properties.publicBaseUrl() + "/a/" + sessionId + "?t="
                        + URLEncoder.encode(token, StandardCharsets.UTF_8);
                model.addAttribute("sessionId", sessionId);
                model.addAttribute("courseName", accepted.courseName());
                model.addAttribute("inAppBrowser", InAppBrowser.detect(request.getHeader(HttpHeaders.USER_AGENT)));
                model.addAttribute("currentUrl", currentUrl);
                model.addAttribute("openInChromeUrl", openInChromeUrl(currentUrl));
                yield "student/landing";
            }
            case ScanOutcome.TokenExpired _ -> message(model, response, HttpStatus.GONE,
                    "This QR code has expired", "The code on the screen changes every few seconds.", SCAN_AGAIN);
            case ScanOutcome.SessionClosed _ -> message(model, response, HttpStatus.GONE,
                    "Check-in is closed", "This attendance session has ended.", null);
            case ScanOutcome.SessionNotFound _ -> message(model, response, HttpStatus.NOT_FOUND,
                    "Session not found", "This link doesn't match an attendance session.", SCAN_AGAIN);
            case ScanOutcome.RateLimited _ -> rateLimited(model, response);
        };
    }

    @GetMapping("/confirm")
    public String confirm(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication,
                          HttpServletRequest request, HttpServletResponse response, Model model) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        Optional<Student> student = checkIns.findStudent(user);
        if (student.isPresent() && checkIns.findRecord(sessionId, student.get()).isPresent()) {
            return "redirect:/a/" + sessionId + "/done";
        }
        PendingCheckIn pending = PendingCheckIn.from(request.getSession(false));
        if (!checkIns.isPendingUsable(sessionId, pending, ClientContext.from(request))) {
            return expiredScan(model, response);
        }
        NameForm form = student.map(s -> new NameForm(s.getFirstName(), s.getLastName()))
                .orElseGet(() -> new NameForm(user.givenName(), user.familyName()));
        return confirmPage(sessionId, user, student.isPresent(), form, model);
    }

    @PostMapping("/check-in")
    public String checkIn(@PathVariable UUID sessionId, @Valid @ModelAttribute("nameForm") NameForm nameForm,
                          BindingResult binding, OAuth2AuthenticationToken authentication,
                          HttpServletRequest request, HttpServletResponse response, Model model,
                          RedirectAttributes redirect) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        boolean registered = checkIns.findStudent(user).isPresent();
        // Names are only read on first registration; once locked, submitted values are ignored.
        if (!registered && binding.hasErrors()) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return confirmPage(sessionId, user, false, nameForm, model);
        }

        PendingCheckIn pending = PendingCheckIn.from(request.getSession(false));
        CheckInOutcome outcome = checkIns.checkIn(sessionId, pending, user, registered ? null : nameForm,
                ClientContext.from(request));
        return switch (outcome) {
            case CheckInOutcome.CheckedIn _ -> {
                PendingCheckIn.clear(request.getSession(false));
                redirect.addFlashAttribute("justCheckedIn", true);
                yield "redirect:/a/" + sessionId + "/done";
            }
            case CheckInOutcome.AlreadyCheckedIn _ -> {
                PendingCheckIn.clear(request.getSession(false));
                yield "redirect:/a/" + sessionId + "/done";
            }
            case CheckInOutcome.NameRequired _ -> {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                yield confirmPage(sessionId, user, false, nameForm, model);
            }
            case CheckInOutcome.DeviceAlreadyUsed _ -> message(model, response, HttpStatus.CONFLICT,
                    "This phone was already used",
                    "Another student already checked in from this device for this session.",
                    "Each phone can check in one student per session. Please use your own phone.");
            case CheckInOutcome.ScanExpired _ -> expiredScan(model, response);
            case CheckInOutcome.SessionClosed _ -> message(model, response, HttpStatus.GONE,
                    "Check-in is closed", "This attendance session has ended.", null);
            case CheckInOutcome.RateLimited _ -> rateLimited(model, response);
        };
    }

    @GetMapping("/done")
    public String done(@PathVariable UUID sessionId, OAuth2AuthenticationToken authentication,
                       HttpServletResponse response, Model model) {
        AuthenticatedUser user = AuthenticatedUser.from(authentication);
        Optional<Student> student = checkIns.findStudent(user);
        Optional<Instant> checkedInAt = student.flatMap(s -> checkIns.findRecord(sessionId, s))
                .map(AttendanceRecord::getCheckedInAt);
        if (checkedInAt.isEmpty()) {
            return message(model, response, HttpStatus.NOT_FOUND,
                    "Not checked in yet", "We have no check-in for you in this session.", SCAN_AGAIN);
        }
        model.addAttribute("firstName", student.get().getFirstName());
        model.addAttribute("lastName", student.get().getLastName());
        model.addAttribute("courseName", checkIns.courseName(sessionId).orElse(""));
        model.addAttribute("checkedInAt", displayTime.time(checkedInAt.get()));
        model.addAttribute("justCheckedIn", Boolean.TRUE.equals(model.getAttribute("justCheckedIn")));
        return "student/done";
    }

    private String confirmPage(UUID sessionId, AuthenticatedUser user, boolean registered, NameForm form,
                               Model model) {
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("courseName", checkIns.courseName(sessionId).orElse(""));
        model.addAttribute("email", user.email());
        model.addAttribute("registered", registered);
        model.addAttribute("nameForm", form);
        return "student/confirm";
    }

    private String expiredScan(Model model, HttpServletResponse response) {
        return message(model, response, HttpStatus.GONE, "Please scan again",
                "Your scan is too old or was made in another browser.", SCAN_AGAIN);
    }

    private String rateLimited(Model model, HttpServletResponse response) {
        return message(model, response, HttpStatus.TOO_MANY_REQUESTS, "Too many attempts",
                "Please wait a minute and try again.", null);
    }

    private static String message(Model model, HttpServletResponse response, HttpStatus status, String title,
                                  String message, String hint) {
        response.setStatus(status.value());
        model.addAttribute("title", title);
        model.addAttribute("message", message);
        model.addAttribute("hint", hint);
        return "student/message";
    }

    /** Android intent URL that reopens the scan link in Chrome, falling back to the plain URL. */
    private static String openInChromeUrl(String url) {
        String withoutScheme = url.replaceFirst("^https?://", "");
        return "intent://" + withoutScheme + "#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url="
                + URLEncoder.encode(url, StandardCharsets.UTF_8) + ";end";
    }
}
