package io.github.sanducc.attendance.emailsignin;

import io.github.sanducc.attendance.common.EmailAddresses;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Mails a sign-in code: plain text plus a minimal HTML part with the code, how long it is valid, "if you didn't ask
 * for this, ignore it" and the app name. No links, images or tracking pixels. The code is also in the subject, so it
 * shows in the phone's notification and the student doesn't have to open the mail app.
 *
 * <p>The SMTP conversation runs on a virtual thread and the request waits at most {@code send-timeout} for it, so a
 * slow mail server can't hang the page (the socket timeouts in {@code spring.mail.properties} bound it too).
 * Logs carry a masked address ({@code a***@x.ro}) and the exception type, never the code, the address or the
 * exception message (SMTP errors quote the recipient).
 */
public class EmailCodeMailer implements DisposableBean {

    static final String APP_NAME = "QR Attendance";

    private static final Logger log = LoggerFactory.getLogger(EmailCodeMailer.class);

    private final JavaMailSender mailSender;
    private final EmailSignInProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public EmailCodeMailer(JavaMailSender mailSender, EmailSignInProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /** Sends {@code code} to {@code email}; false if the mail couldn't be handed to the SMTP server in time. */
    public boolean send(String email, String code) {
        Future<?> sending = executor.submit(() -> {
            mailSender.send(message(email, code));
            return null;
        });
        try {
            sending.get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            sending.cancel(true);
            log.warn("Sign-in code to {} not sent: SMTP server didn't answer within {}", EmailAddresses.mask(email),
                    properties.sendTimeout());
            return false;
        } catch (ExecutionException e) {
            log.warn("Sign-in code to {} not sent: {}", EmailAddresses.mask(email), e.getCause().getClass().getName());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sending.cancel(true);
            return false;
        }
    }

    private MimeMessage message(String email, String code) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                "UTF-8");
        helper.setFrom(properties.from());
        helper.setTo(email);
        helper.setSubject(code + " is your " + APP_NAME + " sign-in code");
        helper.setText(plainText(code), html(code));
        return message;
    }

    String plainText(String code) {
        return """
                Your %s sign-in code is:

                %s

                Type it on the sign-in page in the browser where you asked for it. It is valid for %s and works once.

                If you didn't ask for this code, ignore this email: nobody can sign in without it.

                %s
                """.formatted(APP_NAME, code, validity(), APP_NAME);
    }

    String html(String code) {
        return """
                <!DOCTYPE html>
                <html lang="en"><head><meta charset="utf-8"><title>%s sign-in code</title></head>
                <body style="font-family:system-ui,-apple-system,'Segoe UI',Roboto,Arial,sans-serif;color:#0f172a">
                <p>Your %s sign-in code is:</p>
                <p style="font-size:32px;font-weight:700;letter-spacing:6px;margin:16px 0">%s</p>
                <p>Type it on the sign-in page in the browser where you asked for it. It is valid for %s and works
                once.</p>
                <p style="color:#475569">If you didn't ask for this code, ignore this email: nobody can sign in
                without it.</p>
                <p style="color:#475569">%s</p>
                </body></html>
                """.formatted(APP_NAME, APP_NAME, code, validity(), APP_NAME);
    }

    private String validity() {
        Duration validity = properties.codeValidity();
        long minutes = validity.toMinutes();
        if (minutes >= 1 && validity.equals(Duration.ofMinutes(minutes))) {
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        return validity.toSeconds() + " seconds";
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
