package io.github.sanducc.attendance.checkin;

import io.github.sanducc.attendance.checkin.CheckInService.CheckInOutcome;
import io.github.sanducc.attendance.checkin.CheckInService.ScanOutcome;
import io.github.sanducc.attendance.checkin.CheckInService.SessionRules;
import io.github.sanducc.attendance.common.AuthenticatedUser;
import io.github.sanducc.attendance.common.ClientContext;
import io.github.sanducc.attendance.common.DisplayTime;
import io.github.sanducc.attendance.common.InAppBrowser;
import io.github.sanducc.attendance.config.AttendanceProperties;
import io.github.sanducc.attendance.emailsignin.EmailSignInProperties;
import io.github.sanducc.attendance.course.WorkspaceDomains;
import io.github.sanducc.attendance.session.Geofence;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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

/**
 * Student flow: scan → sign in (Google, or the email code of #52) → confirm name → check in → done. Both
 * sign-ins lead to the same pages and the same rules; {@link AuthenticatedUser} tells them apart by provider.
 */
@Controller
@RequestMapping("/a/{sessionId}")
public class ScanController {

    private static final String SCAN_AGAIN = "Scan the QR code on the screen again.";

    private final CheckInService checkIns;
    private final DisplayTime displayTime;
    private final AttendanceProperties properties;
    private final EmailSignInProperties emailSignIn;

    public ScanController(CheckInService checkIns, DisplayTime displayTime, AttendanceProperties properties,
                          EmailSignInProperties emailSignIn) {
        this.checkIns = checkIns;
        this.displayTime = displayTime;
        this.properties = properties;
        this.emailSignIn = emailSignIn;
    }

    @GetMapping
    public String scan(@PathVariable UUID sessionId, @RequestParam(name = "t", required = false) String token,
                       Authentication authentication, HttpServletRequest request, HttpServletResponse response,
                       Model model) {
        ScanOutcome outcome = checkIns.scan(sessionId, token, ClientContext.from(request));
        return switch (outcome) {
            case ScanOutcome.Accepted accepted -> {
                accepted.pending().storeIn(request.getSession(true));
                if (AuthenticatedUser.isStudentSignIn(authentication)) {
                    yield "redirect:/a/" + sessionId + "/confirm";
                }
                String currentUrl = properties.publicBaseUrl() + "/a/" + sessionId + "?t="
                        + URLEncoder.encode(token, StandardCharsets.UTF_8);
                SessionRules rules = checkIns.rules(sessionId);
                model.addAttribute("sessionId", sessionId);
                model.addAttribute("courseName", accepted.courseName());
                model.addAttribute("allowedDomains", WorkspaceDomains.describe(rules.allowedDomains()));
                model.addAttribute("locationRequired", rules.locationRequired());
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
    public String confirm(@PathVariable UUID sessionId, Authentication authentication,
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
        Optional<List<String>> wrongDomain = checkIns.domainLockViolation(sessionId, user);
        if (wrongDomain.isPresent()) {
            return wrongDomain(wrongDomain.get(), user, model, response);
        }
        NameForm form = student.map(s -> new NameForm(s.getFirstName(), s.getLastName()))
                .orElseGet(() -> new NameForm(user.givenName(), user.familyName()));
        return confirmPage(sessionId, user, student.isPresent(), form, model);
    }

    /**
     * {@code latitude}, {@code longitude} and {@code accuracy} are filled in by checkin-location.js on sessions with
     * a location check (rule 6) and ignored otherwise. Taken as strings: anything unparseable counts as missing.
     */
    @PostMapping("/check-in")
    public String checkIn(@PathVariable UUID sessionId, @Valid @ModelAttribute("nameForm") NameForm nameForm,
                          BindingResult binding,
                          @RequestParam(name = "latitude", required = false) String latitude,
                          @RequestParam(name = "longitude", required = false) String longitude,
                          @RequestParam(name = "accuracy", required = false) String accuracy,
                          Authentication authentication,
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
                Geofence.Location.parse(latitude, longitude, accuracy), ClientContext.from(request));
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
            case CheckInOutcome.WrongDomain wrong -> wrongDomain(wrong.allowedDomains(), user, model, response);
            case CheckInOutcome.LocationRequired _ -> {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                model.addAttribute("locationError", "This class checks that you're in the room. Allow location "
                        + "access when your phone asks, then tap Check in again.");
                yield confirmPage(sessionId, user, registered, registered ? lockedNames(user) : nameForm, model);
            }
            case CheckInOutcome.OutsideGeofence outside -> {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                model.addAttribute("locationError", "You seem to be " + approximately(outside.distanceMeters())
                        + " from the classroom, so you can't check in from there. If you are in the room, move "
                        + "closer to a window for a better signal and try again.");
                yield confirmPage(sessionId, user, registered, registered ? lockedNames(user) : nameForm, model);
            }
        };
    }

    @GetMapping("/done")
    public String done(@PathVariable UUID sessionId, Authentication authentication,
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
        SessionRules rules = checkIns.rules(sessionId);
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("courseName", rules.courseName());
        model.addAttribute("email", user.email());
        model.addAttribute("registered", registered);
        model.addAttribute("nameForm", form);
        model.addAttribute("locationRequired", rules.locationRequired());
        return "student/confirm";
    }

    /** The registered student's locked names, for re-showing the confirm page. */
    private NameForm lockedNames(AuthenticatedUser user) {
        return checkIns.findStudent(user)
                .map(s -> new NameForm(s.getFirstName(), s.getLastName()))
                .orElseGet(NameForm::new);
    }

    /**
     * Rule 7. The way out keeps the scan: signing in again (account chooser, with the domain as a hint, or another
     * email address) rotates the session id but keeps the pending check-in, so the student can finish within the
     * grace period. For the email sign-in the address's own domain is compared (its inbox was proven by the code).
     */
    private String wrongDomain(List<String> allowedDomains, AuthenticatedUser user, Model model,
                               HttpServletResponse response) {
        String domains = WorkspaceDomains.describe(allowedDomains);
        String signedInAs = user.email() != null ? "You're signed in as " + user.email() + ". " : "";
        if (user.provider() == Provider.EMAIL) {
            model.addAttribute("actionUrl", "/login/email");
            model.addAttribute("actionLabel", "Use another email address");
            return message(model, response, HttpStatus.FORBIDDEN, "Use your school address",
                    "This course only accepts " + domains + " addresses.",
                    signedInAs + "Sign in with your " + domains + " email address to check in.");
        }
        model.addAttribute("actionUrl", "/oauth2/authorization/google");
        model.addAttribute("actionLabel", "Choose another Google account");
        model.addAttribute("actionGoogle", true);
        if (emailSignIn.enabled()) {
            model.addAttribute("secondaryUrl", "/login/email");
            model.addAttribute("secondaryLabel", "Sign in with your " + domains + " email address instead");
        }
        return message(model, response, HttpStatus.FORBIDDEN, "Use your school account",
                "This course only accepts " + domains + " accounts.",
                signedInAs + "Sign in with your " + domains + " Google account to check in.");
    }

    /** "about 340 m" / "about 2.3 km": rounded, so it doesn't pretend to GPS precision. */
    static String approximately(long meters) {
        if (meters >= 1000) {
            return "about " + String.format(Locale.ROOT, "%.1f", meters / 1000.0) + " km";
        }
        return "about " + Math.max(10, Math.round(meters / 10.0) * 10) + " m";
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
