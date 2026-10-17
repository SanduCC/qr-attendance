package io.github.sanducc.attendance.support;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Plays the SMTP server for the email sign-in (#52): the app's real {@code EmailCodeMailer} builds and "sends" the
 * MIME message, and this keeps it so tests can read the code out of it, like a student reading their inbox. Boot's
 * own mail sender backs off (it only exists without another {@code JavaMailSender}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeMailConfiguration {

    @Bean
    public CapturingMailSender capturingMailSender() {
        return new CapturingMailSender();
    }

    /** A {@link JavaMailSenderImpl} whose transport is a list, so the app's TLS rules apply to it as to the real one. */
    public static final class CapturingMailSender extends JavaMailSenderImpl {

        private static final Pattern CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

        private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();
        private volatile Supplier<MailException> failure;

        public CapturingMailSender() {
            setHost("smtp.test.invalid");
            setPort(587);
        }

        @Override
        protected void doSend(MimeMessage[] mimeMessages, Object[] originalMessages) {
            Supplier<MailException> fail = failure;
            if (fail != null) {
                throw fail.get();
            }
            for (MimeMessage message : mimeMessages) {
                try {
                    // What the real transport does first: sets Content-Type and the other derived headers.
                    message.saveChanges();
                } catch (MessagingException e) {
                    throw new IllegalStateException(e);
                }
                sent.add(message);
            }
        }

        /** Makes every send throw (e.g. the SMTP server rejecting the recipient) until {@link #reset()}. */
        public void failWith(Supplier<MailException> failure) {
            this.failure = failure;
        }

        public void reset() {
            sent.clear();
            failure = null;
        }

        public List<MimeMessage> sent() {
            return List.copyOf(sent);
        }

        public List<MimeMessage> sentTo(String address) {
            List<MimeMessage> result = new ArrayList<>();
            for (MimeMessage message : sent) {
                try {
                    if (message.getAllRecipients() != null && message.getAllRecipients().length == 1
                            && message.getAllRecipients()[0].toString().equalsIgnoreCase(address)) {
                        result.add(message);
                    }
                } catch (MessagingException e) {
                    throw new IllegalStateException(e);
                }
            }
            return result;
        }

        /** The code in the newest mail to {@code address}, read from its plain-text part. */
        public String lastCodeTo(String address) {
            List<MimeMessage> messages = sentTo(address);
            if (messages.isEmpty()) {
                throw new AssertionError("No mail sent to " + address);
            }
            Matcher matcher = CODE.matcher(plainText(messages.getLast()));
            if (!matcher.find()) {
                throw new AssertionError("No 6-digit code in the mail to " + address);
            }
            return matcher.group(1);
        }

        public static String plainText(MimeMessage message) {
            return part(message, "text/plain");
        }

        public static String html(MimeMessage message) {
            return part(message, "text/html");
        }

        private static String part(Part part, String type) {
            try {
                Object content = part.getContent();
                if (content instanceof String text) {
                    return part.isMimeType(type) ? text : null;
                }
                if (content instanceof Multipart multipart) {
                    for (int i = 0; i < multipart.getCount(); i++) {
                        BodyPart child = multipart.getBodyPart(i);
                        String found = part(child, type);
                        if (found != null) {
                            return found;
                        }
                    }
                }
                return null;
            } catch (MessagingException | IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
