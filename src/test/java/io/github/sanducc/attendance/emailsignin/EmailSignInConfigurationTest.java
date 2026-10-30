package io.github.sanducc.attendance.emailsignin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sanducc.attendance.common.EmailAddresses;
import io.github.sanducc.attendance.common.PepperedHasher;
import io.github.sanducc.attendance.support.FakeMailConfiguration.CapturingMailSender;
import io.github.sanducc.attendance.support.TestProperties;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import org.springframework.mail.MailSendException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Startup checks of the email sign-in (#52): on only with a mail server and sender, and never without TLS. */
class EmailSignInConfigurationTest {

    private static final PepperedHasher HASHER = new PepperedHasher(TestProperties.defaults());

    private static EmailSignInProperties properties(String from) {
        return new EmailSignInProperties(true, from, Duration.ofMinutes(10), 5, Duration.ofSeconds(30),
                Duration.ofSeconds(10), new EmailSignInProperties.Limits(3, 5, 60, 250, 10, 150));
    }

    private static JavaMailSenderImpl smtp(String host, int port) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        return sender;
    }

    @Test
    void anyPortButSmtpsRequiresStartTls() {
        JavaMailSenderImpl sender = smtp("smtp-relay.example.com", 587);
        // Even if the configuration tried to turn it off.
        sender.getJavaMailProperties().setProperty("mail.smtp.starttls.required", "false");

        EmailSignInConfiguration.requireTls(sender);

        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }

    @Test
    void port465IsSmtps() {
        JavaMailSenderImpl sender = smtp("smtp.example.com", 465);

        EmailSignInConfiguration.requireTls(sender);

        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.ssl.enable", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }

    @Test
    void refusesToStartWithoutAMailServerOrSender() {
        EmailSignInConfiguration configuration = new EmailSignInConfiguration();
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        assertThatThrownBy(() -> configuration.emailCodeMailer(none.getBeanProvider(JavaMailSender.class),
                properties("QR Attendance <a@example.ro>"), HASHER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_HOST");

        StaticListableBeanFactory blankHost = new StaticListableBeanFactory();
        blankHost.addBean("mailSender", smtp("", 587));
        assertThatThrownBy(() -> configuration.emailCodeMailer(blankHost.getBeanProvider(JavaMailSender.class),
                properties("QR Attendance <a@example.ro>"), HASHER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_HOST is empty");

        StaticListableBeanFactory configured = new StaticListableBeanFactory();
        configured.addBean("mailSender", smtp("smtp.example.com", 587));
        assertThatThrownBy(() -> configuration.emailCodeMailer(configured.getBeanProvider(JavaMailSender.class),
                properties(null), HASHER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
        assertThatThrownBy(() -> configuration.emailCodeMailer(configured.getBeanProvider(JavaMailSender.class),
                properties("not an address"), HASHER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");

        assertThat(configuration.emailCodeMailer(configured.getBeanProvider(JavaMailSender.class),
                properties("QR Attendance <attendance@example.ro>"), HASHER)).isNotNull();
    }

    @Test
    void theMailerBuildsAPlainTextAndHtmlMessageWithTheCodeInTheSubject() throws Exception {
        CapturingMailSender sender = new CapturingMailSender();
        EmailCodeMailer mailer = new EmailCodeMailer(sender, properties("QR Attendance <attendance@example.ro>"),
                HASHER);

        assertThat(mailer.send("ana@example.ro", "042917")).isTrue();

        assertThat(sender.lastCodeTo("ana@example.ro")).isEqualTo("042917");
        MimeMessage message = sender.sent().getFirst();
        assertThat(message.getSubject()).isEqualTo("042917 is your QR Attendance sign-in code");
        assertThat(message.getFrom()[0].toString()).isEqualTo("QR Attendance <attendance@example.ro>");
        assertThat(CapturingMailSender.plainText(message)).contains("042917").doesNotContain("<");
        assertThat(CapturingMailSender.html(message)).contains("042917").contains("<html");

        sender.failWith(() -> new MailSendException("550 rejected ana@example.ro"));
        assertThat(mailer.send("ana@example.ro", "111111")).isFalse();
        mailer.destroy();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailedSendIsLoggedWithTheAddressTagOnly(CapturedOutput output) {
        CapturingMailSender sender = new CapturingMailSender();
        EmailCodeMailer mailer = new EmailCodeMailer(sender, properties("a@example.ro"), HASHER);
        sender.failWith(() -> new MailSendException("550 rejected ana@example.ro"));

        assertThat(mailer.send("ana@example.ro", "111111")).isFalse();

        assertThat(output.getAll())
                .contains("Sign-in code to " + EmailAddresses.logId(HASHER, "ana@example.ro") + " not sent: "
                        + MailSendException.class.getName())
                .doesNotContain("ana@").doesNotContain("a***@").doesNotContain("111111");
        mailer.destroy();
    }

    @Test
    void theMailSaysHowLongTheCodeIsValid() {
        EmailCodeMailer mailer = new EmailCodeMailer(smtp("smtp.example.com", 587), properties("a@example.ro"), HASHER);
        assertThat(mailer.plainText("123456")).contains("123456").contains("valid for 10 minutes")
                .contains("If you didn't ask for this code, ignore this email");
        assertThat(mailer.html("123456")).contains("123456").contains("valid for 10 minutes")
                .doesNotContain("<img").doesNotContain("<a ");
        mailer.destroy();
    }
}
