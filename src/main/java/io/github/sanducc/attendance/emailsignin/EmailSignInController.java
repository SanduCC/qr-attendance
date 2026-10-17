package io.github.sanducc.attendance.emailsignin;

import io.github.sanducc.attendance.checkin.CheckInService;
import io.github.sanducc.attendance.checkin.PendingCheckIn;
import io.github.sanducc.attendance.common.ClientContext;
import io.github.sanducc.attendance.common.DeviceIdFilter;
import io.github.sanducc.attendance.common.EmailAddresses;
import io.github.sanducc.attendance.common.EmailAuthenticationToken;
import io.github.sanducc.attendance.config.LoginSuccessHandler;
import io.github.sanducc.attendance.course.WorkspaceDomains;
import io.github.sanducc.attendance.emailsignin.EmailSignInService.SendOutcome;
import io.github.sanducc.attendance.emailsignin.EmailSignInService.VerifyOutcome;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * "No Google account? Sign in with email" (#52): email → 6-digit code by mail → the code typed <b>on this page, in
 * this browser</b> → signed in as a student → the usual confirm-name and check-in. A code, not a magic link: a link
 * would open in the mail app's own browser and lose this browser's session, which holds the scanned QR token (rule
 * 2: bound to the session, unusable from another browser).
 *
 * <p>Signing in works like the Google login: the session id rotates (session fixation) but keeps its attributes,
 * so the pending check-in survives; the CSRF token is renewed; then {@link LoginSuccessHandler} sends a student who
 * just scanned to the confirm page. The grace period since the scan is unchanged.
 */
@Controller
@RequestMapping(DeviceIdFilter.EMAIL_SIGN_IN_PATH)
@ConditionalOnProperty(prefix = "attendance.email-sign-in", name = "enabled", havingValue = "true")
public class EmailSignInController {

    private static final String START_VIEW = "student/email";
    private static final String CODE_VIEW = "student/email-code";
    private static final String TO_START = "redirect:" + DeviceIdFilter.EMAIL_SIGN_IN_PATH;
    private static final String TO_CODE = "redirect:" + DeviceIdFilter.EMAIL_SIGN_IN_PATH + "/code";
    private static final String TOO_MANY_SENDS = "Too many codes were requested. Wait a few minutes, then try again.";
    private static final String SEND_FAILED = "We couldn't send the code. Please try again.";

    private final EmailSignInService signIn;
    private final CheckInService checkIns;
    private final EmailSignInProperties properties;
    private final LoginSuccessHandler successHandler;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();
    private final Clock clock;

    public EmailSignInController(EmailSignInService signIn, CheckInService checkIns,
                                 EmailSignInProperties properties, LoginSuccessHandler successHandler,
                                 CsrfTokenRepository csrfTokenRepository, Clock clock) {
        this.signIn = signIn;
        this.checkIns = checkIns;
        this.properties = properties;
        this.successHandler = successHandler;
        // What Spring Security does on every login: new session id (attributes kept), new CSRF token.
        this.sessionStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokenRepository)));
        this.clock = clock;
    }

    @GetMapping
    public String start(HttpServletRequest request, Model model) {
        EmailSignInAttempt attempt = EmailSignInAttempt.from(request.getSession(false));
        return startPage(attempt == null ? "" : attempt.email(), null, request.getSession(false), model);
    }

    /** The form's CSRF token lives in the HTTP session, so a request that gets here has one. */
    @PostMapping
    public String send(@RequestParam(name = "email", required = false) String email, HttpServletRequest request,
                       HttpServletResponse response, Model model) {
        HttpSession session = request.getSession();
        EmailSignInAttempt previous = EmailSignInAttempt.from(session);
        Optional<String> normalized = EmailAddresses.normalize(email);
        if (previous != null && normalized.isPresent() && normalized.get().equals(previous.email())
                && cooldownLeft(previous).isPositive()) {
            // Sent a moment ago (double tap, back button): show the code page instead of mailing another code.
            return TO_CODE;
        }
        SendOutcome outcome = signIn.sendCode(session.getId(), email, allowedDomains(session),
                ClientContext.from(request));
        return switch (outcome) {
            case SendOutcome.Sent sent -> {
                new EmailSignInAttempt(sent.email(), clock.instant()).storeIn(session);
                yield TO_CODE;
            }
            case SendOutcome.InvalidEmail _ -> error(response, HttpStatus.BAD_REQUEST,
                    startPage(email, "Enter a valid email address, e.g. ana.popescu@example.ro.", session, model));
            case SendOutcome.WrongDomain wrong -> error(response, HttpStatus.FORBIDDEN,
                    startPage(email, "This course only accepts " + WorkspaceDomains.describe(wrong.allowedDomains())
                            + " addresses. Use your school email address.", session, model));
            case SendOutcome.RateLimited _ -> error(response, HttpStatus.TOO_MANY_REQUESTS,
                    startPage(email, TOO_MANY_SENDS, session, model));
            case SendOutcome.SendFailed _ -> error(response, HttpStatus.SERVICE_UNAVAILABLE,
                    startPage(email, SEND_FAILED, session, model));
        };
    }

    @GetMapping("/code")
    public String code(HttpServletRequest request, Model model) {
        EmailSignInAttempt attempt = EmailSignInAttempt.from(request.getSession(false));
        if (attempt == null) {
            return TO_START;
        }
        return codePage(attempt, null, model);
    }

    /**
     * On success the response is the login redirect ({@link LoginSuccessHandler}) and this returns null: with the
     * {@code HttpServletResponse} parameter, Spring MVC then treats the request as handled and renders nothing.
     */
    @PostMapping("/code")
    public String verify(@RequestParam(name = "code", required = false) String code, HttpServletRequest request,
                         HttpServletResponse response, Model model) throws IOException, ServletException {
        HttpSession session = request.getSession(false);
        EmailSignInAttempt attempt = EmailSignInAttempt.from(session);
        if (attempt == null) {
            return TO_START;
        }
        VerifyOutcome outcome = signIn.verify(session.getId(), attempt.email(), code, ClientContext.from(request));
        return switch (outcome) {
            case VerifyOutcome.SignedIn signedIn -> {
                signIn(signedIn.email(), request, response);
                yield null;
            }
            case VerifyOutcome.WrongCode wrong -> error(response, HttpStatus.BAD_REQUEST, codePage(attempt,
                    "That code isn't right. " + wrong.attemptsLeft()
                            + (wrong.attemptsLeft() == 1 ? " try" : " tries") + " left.", model));
            case VerifyOutcome.TooManyAttempts _ -> error(response, HttpStatus.BAD_REQUEST, codePage(attempt,
                    "Too many wrong codes, so this one no longer works. Send a new code.", model));
            case VerifyOutcome.NoCode _ -> error(response, HttpStatus.BAD_REQUEST, codePage(attempt,
                    "This code has expired or was replaced by a newer one. Send a new code.", model));
            case VerifyOutcome.Malformed _ -> error(response, HttpStatus.BAD_REQUEST, codePage(attempt,
                    "Enter the 6-digit code from the email.", model));
            case VerifyOutcome.RateLimited _ -> error(response, HttpStatus.TOO_MANY_REQUESTS, codePage(attempt,
                    "Too many attempts. Wait a few minutes, then try again.", model));
        };
    }

    @PostMapping("/resend")
    public String resend(HttpServletRequest request, HttpServletResponse response, Model model,
                         RedirectAttributes redirect) {
        HttpSession session = request.getSession(false);
        EmailSignInAttempt attempt = EmailSignInAttempt.from(session);
        if (attempt == null) {
            return TO_START;
        }
        Duration wait = cooldownLeft(attempt);
        if (wait.isPositive()) {
            return error(response, HttpStatus.TOO_MANY_REQUESTS, codePage(attempt,
                    "Wait " + seconds(wait) + " seconds before sending a new code.", model));
        }
        SendOutcome outcome = signIn.sendCode(session.getId(), attempt.email(), allowedDomains(session),
                ClientContext.from(request));
        return switch (outcome) {
            case SendOutcome.Sent sent -> {
                new EmailSignInAttempt(sent.email(), clock.instant()).storeIn(session);
                redirect.addFlashAttribute("resent", true);
                yield TO_CODE;
            }
            case SendOutcome.RateLimited _ -> error(response, HttpStatus.TOO_MANY_REQUESTS,
                    codePage(attempt, TOO_MANY_SENDS, model));
            case SendOutcome.SendFailed _ -> error(response, HttpStatus.SERVICE_UNAVAILABLE,
                    codePage(attempt, SEND_FAILED, model));
            // The address passed both checks when the first code was sent; start over if that changed.
            case SendOutcome.InvalidEmail _, SendOutcome.WrongDomain _ -> TO_START;
        };
    }

    private void signIn(String email, HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException {
        EmailSignInAttempt.clear(request.getSession(false));
        Authentication authentication = new EmailAuthenticationToken(email);
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        successHandler.onAuthenticationSuccess(request, response, authentication);
    }

    private String startPage(String email, String error, HttpSession session, Model model) {
        model.addAttribute("email", email == null ? "" : email.strip());
        model.addAttribute("error", error);
        model.addAttribute("allowedDomains", WorkspaceDomains.describe(allowedDomains(session)));
        model.addAttribute("validityMinutes", properties.codeValidity().toMinutes());
        return START_VIEW;
    }

    private String codePage(EmailSignInAttempt attempt, String error, Model model) {
        model.addAttribute("email", attempt.email());
        model.addAttribute("error", error);
        model.addAttribute("validityMinutes", properties.codeValidity().toMinutes());
        model.addAttribute("cooldownSeconds", seconds(cooldownLeft(attempt)));
        model.addAttribute("resent", Boolean.TRUE.equals(model.getAttribute("resent")));
        return CODE_VIEW;
    }

    /** Rule 7 of the course this browser scanned (if any), so a wrong address is refused before a mail goes out. */
    private List<String> allowedDomains(HttpSession session) {
        PendingCheckIn pending = PendingCheckIn.from(session);
        return pending == null ? List.of() : checkIns.rules(pending.sessionId()).allowedDomains();
    }

    private Duration cooldownLeft(EmailSignInAttempt attempt) {
        Instant allowedAt = attempt.sentAt().plus(properties.resendCooldown());
        Duration left = Duration.between(clock.instant(), allowedAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static long seconds(Duration duration) {
        return (duration.toMillis() + 999) / 1000;
    }

    private static String error(HttpServletResponse response, HttpStatus status, String view) {
        response.setStatus(status.value());
        return view;
    }
}
