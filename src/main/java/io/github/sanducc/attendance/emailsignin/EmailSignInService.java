package io.github.sanducc.attendance.emailsignin;

import io.github.sanducc.attendance.common.ClientContext;
import io.github.sanducc.attendance.common.EmailAddresses;
import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.common.RateLimiter;
import io.github.sanducc.attendance.course.WorkspaceDomains;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/**
 * Email sign-in rules (#52): who gets a code, and whether a typed code signs them in. Students only; the result of a
 * successful check is an address whose inbox ownership was just proven, nothing more.
 *
 * <p>Anti-abuse (Redis fixed windows, {@code attendance.email-sign-in.limits}): code sends per device cookie, per IP,
 * per target address and app-wide per day; code entries per browser session and per IP. The answers never say
 * whether an address already has an account: every valid address gets the same "we sent a code" page.
 */
public class EmailSignInService {

    private static final Logger log = LoggerFactory.getLogger(EmailSignInService.class);
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    /** Spaces and dashes people type or paste inside a code ("123 456", "123-456"). */
    private static final Pattern CODE_SEPARATORS = Pattern.compile("[\\s\\-]");
    private static final Duration FIFTEEN_MINUTES = Duration.ofMinutes(15);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Duration ONE_DAY = Duration.ofDays(1);

    private final EmailCodeStore codes;
    private final EmailCodeMailer mailer;
    private final RateLimiter rateLimiter;
    private final PepperedHasher hasher;
    private final EmailSignInProperties properties;

    public EmailSignInService(EmailCodeStore codes, EmailCodeMailer mailer, RateLimiter rateLimiter,
                              PepperedHasher hasher, EmailSignInProperties properties) {
        this.codes = codes;
        this.mailer = mailer;
        this.rateLimiter = rateLimiter;
        this.hasher = hasher;
        this.properties = properties;
    }

    public sealed interface SendOutcome {
        record Sent(String email) implements SendOutcome {}
        /** Failed the basic syntax check: nothing was sent. */
        record InvalidEmail() implements SendOutcome {}
        /** The scanned course is domain-locked (rule 7) and this address isn't on one of its domains. */
        record WrongDomain(List<String> allowedDomains) implements SendOutcome {}
        record RateLimited() implements SendOutcome {}
        record SendFailed() implements SendOutcome {}
    }

    public sealed interface VerifyOutcome {
        record SignedIn(String email) implements VerifyOutcome {}
        record WrongCode(int attemptsLeft) implements VerifyOutcome {}
        record TooManyAttempts() implements VerifyOutcome {}
        /** Expired, already used, replaced by a newer code, or requested in another browser. */
        record NoCode() implements VerifyOutcome {}
        /** Not six digits: doesn't count as an attempt (it can't match anyway). */
        record Malformed() implements VerifyOutcome {}
        record RateLimited() implements VerifyOutcome {}
    }

    /**
     * Mails a new code for {@code rawEmail}, bound to {@code httpSessionId}. {@code allowedDomains} are the domain
     * lock of the course this browser scanned (empty = none): a mismatching address is refused before anything is
     * sent; the check-in enforces the lock again.
     */
    public SendOutcome sendCode(String httpSessionId, String rawEmail, List<String> allowedDomains,
                                ClientContext client) {
        Optional<String> normalized = EmailAddresses.normalize(rawEmail);
        if (normalized.isEmpty()) {
            return new SendOutcome.InvalidEmail();
        }
        String email = normalized.get();
        if (!WorkspaceDomains.allows(allowedDomains, EmailAddresses.domainOf(email))) {
            return new SendOutcome.WrongDomain(allowedDomains);
        }
        if (!withinSendLimits(email, client)) {
            log.info("Sign-in code to {} refused: rate limit", logId(email));
            return new SendOutcome.RateLimited();
        }
        String code;
        try {
            code = codes.issue(httpSessionId, email);
        } catch (DataAccessException e) {
            log.warn("Sign-in code to {} not stored: {}", logId(email), e.getClass().getName());
            return new SendOutcome.SendFailed();
        }
        if (!mailer.send(email, code)) {
            discardQuietly(httpSessionId, email);
            return new SendOutcome.SendFailed();
        }
        log.info("Sign-in code sent to {}", logId(email));
        return new SendOutcome.Sent(email);
    }

    /** Checks a typed code against the one sent to {@code email} for this browser session. */
    public VerifyOutcome verify(String httpSessionId, String email, String rawCode, ClientContext client) {
        String code = rawCode == null ? "" : CODE_SEPARATORS.matcher(rawCode).replaceAll("");
        if (!SIX_DIGITS.matcher(code).matches()) {
            return new VerifyOutcome.Malformed();
        }
        if (!withinVerifyLimits(httpSessionId, client)) {
            return new VerifyOutcome.RateLimited();
        }
        EmailCodeStore.Result result;
        try {
            result = codes.verify(httpSessionId, email, code);
        } catch (DataAccessException e) {
            log.warn("Sign-in code check failed: {}", e.getClass().getName());
            return new VerifyOutcome.NoCode();
        }
        return switch (result) {
            case EmailCodeStore.Result.Accepted _ -> {
                log.info("Signed in with an email code: {}", logId(email));
                yield new VerifyOutcome.SignedIn(email);
            }
            case EmailCodeStore.Result.Wrong wrong -> new VerifyOutcome.WrongCode(wrong.attemptsLeft());
            case EmailCodeStore.Result.TooManyAttempts _ -> {
                log.info("Sign-in code for {} invalidated after too many wrong entries", logId(email));
                yield new VerifyOutcome.TooManyAttempts();
            }
            case EmailCodeStore.Result.NoCode _ -> new VerifyOutcome.NoCode();
        };
    }

    /**
     * Checked in order and stopping at the first refusal, so a blocked device or IP can't use up a victim
     * address's quota (or the app-wide one).
     */
    private boolean withinSendLimits(String email, ClientContext client) {
        EmailSignInProperties.Limits limits = properties.limits();
        return rateLimiter.tryAcquire("mail-dev:" + hasher.hash("device", client.deviceId()),
                        limits.sendsPerDevicePer15Min(), FIFTEEN_MINUTES)
                && rateLimiter.tryAcquire("mail-ip:" + hasher.hash("ip", client.ip()),
                        limits.sendsPerIpPerHour(), ONE_HOUR)
                && rateLimiter.tryAcquire("mail-to:" + hasher.hash("email-limit", email),
                        limits.sendsPerEmailPer15Min(), FIFTEEN_MINUTES)
                && rateLimiter.tryAcquire("mail-all", limits.sendsPerDay(), ONE_DAY);
    }

    private boolean withinVerifyLimits(String httpSessionId, ClientContext client) {
        EmailSignInProperties.Limits limits = properties.limits();
        return rateLimiter.tryAcquire("code-ses:" + hasher.hash("http-session", httpSessionId),
                        limits.verifiesPerSessionPer15Min(), FIFTEEN_MINUTES)
                && rateLimiter.tryAcquire("code-ip:" + hasher.hash("ip", client.ip()),
                        limits.verifiesPerIpPer15Min(), FIFTEEN_MINUTES);
    }

    /** Logs name an address only by its peppered tag, never the address or a part of it. */
    private String logId(String email) {
        return EmailAddresses.logId(hasher, email);
    }

    private void discardQuietly(String httpSessionId, String email) {
        try {
            codes.discard(httpSessionId, email);
        } catch (DataAccessException e) {
            log.debug("Couldn't discard an unsent code: {}", e.getClass().getName());
        }
    }
}
