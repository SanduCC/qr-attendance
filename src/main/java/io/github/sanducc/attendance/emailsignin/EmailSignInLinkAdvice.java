package io.github.sanducc.attendance.emailsignin;

import io.github.sanducc.attendance.checkin.ScanController;
import io.github.sanducc.attendance.common.HomeController;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** {@code emailSignIn} in the model of the pages that offer "No Google account? Sign in with email" (#52). */
@ControllerAdvice(assignableTypes = {HomeController.class, ScanController.class, EmailSignInController.class})
public class EmailSignInLinkAdvice {

    private final EmailSignInProperties properties;

    public EmailSignInLinkAdvice(EmailSignInProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute("emailSignIn")
    public boolean emailSignIn() {
        return properties.enabled();
    }
}
