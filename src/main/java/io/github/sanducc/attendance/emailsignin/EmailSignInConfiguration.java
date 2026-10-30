package io.github.sanducc.attendance.emailsignin;

import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.common.RateLimiter;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.util.Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The email sign-in beans (#52), only with {@code attendance.email-sign-in.enabled=true}. Fails at startup, with a
 * clear message, when it is on but mail isn't configured, so a misconfiguration shows at deploy time rather than as
 * "couldn't send the code" in class.
 *
 * <p>TLS is not optional: on port 465 the connection is SMTPS (implicit TLS), on any other port STARTTLS is
 * required (the send fails rather than falling back to plain text), and the server certificate's host name is
 * checked in both cases. Set here, on top of {@code spring.mail.properties}, so no configuration can turn it off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "attendance.email-sign-in", name = "enabled", havingValue = "true")
public class EmailSignInConfiguration {

    static final int SMTPS_PORT = 465;

    @Bean
    EmailCodeStore emailCodeStore(StringRedisTemplate redis, PepperedHasher hasher,
                                  EmailSignInProperties properties, Clock clock) {
        return new EmailCodeStore(redis, hasher, properties, clock);
    }

    @Bean
    EmailCodeMailer emailCodeMailer(ObjectProvider<JavaMailSender> mailSenders, EmailSignInProperties properties,
                                    PepperedHasher hasher) {
        JavaMailSender mailSender = mailSenders.getIfAvailable();
        if (mailSender == null) {
            throw new IllegalStateException("attendance.email-sign-in.enabled is true but no mail server is "
                    + "configured: set MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD and MAIL_FROM");
        }
        requireSender(properties.from());
        if (mailSender instanceof JavaMailSenderImpl smtp) {
            requireTls(smtp);
        }
        return new EmailCodeMailer(mailSender, properties, hasher);
    }

    @Bean
    EmailSignInService emailSignInService(EmailCodeStore codes, EmailCodeMailer mailer, RateLimiter rateLimiter,
                                          PepperedHasher hasher, EmailSignInProperties properties) {
        return new EmailSignInService(codes, mailer, rateLimiter, hasher, properties);
    }

    static void requireSender(String from) {
        if (from == null) {
            throw new IllegalStateException("attendance.email-sign-in.enabled is true but MAIL_FROM is empty");
        }
        try {
            InternetAddress address = new InternetAddress(from, true);
            address.validate();
        } catch (AddressException e) {
            throw new IllegalStateException("MAIL_FROM isn't a valid address, e.g. "
                    + "\"QR Attendance <attendance@example.ro>\"");
        }
    }

    static void requireTls(JavaMailSenderImpl smtp) {
        if (smtp.getHost() == null || smtp.getHost().isBlank()) {
            throw new IllegalStateException("attendance.email-sign-in.enabled is true but MAIL_HOST is empty");
        }
        Properties mail = smtp.getJavaMailProperties();
        String protocol = smtp.getProtocol() == null ? "smtp" : smtp.getProtocol();
        if (smtp.getPort() == SMTPS_PORT || "smtps".equals(protocol)) {
            mail.setProperty("mail." + protocol + ".ssl.enable", "true");
        } else {
            mail.setProperty("mail." + protocol + ".starttls.enable", "true");
            mail.setProperty("mail." + protocol + ".starttls.required", "true");
        }
        mail.setProperty("mail." + protocol + ".ssl.checkserveridentity", "true");
    }
}
