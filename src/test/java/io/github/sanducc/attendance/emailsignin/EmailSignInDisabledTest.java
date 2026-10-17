package io.github.sanducc.attendance.emailsignin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.sanducc.attendance.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/** The default (#52): with {@code attendance.email-sign-in.enabled=false} there is no route, no link and no bean. */
@TestPropertySource(properties = "attendance.email-sign-in.enabled=false")
class EmailSignInDisabledTest extends IntegrationTestSupport {

    @Autowired private ApplicationContext context;

    @Test
    void thereIsNoRouteNoLinkAndNoBean() throws Exception {
        assertThat(context.getBeansOfType(EmailSignInService.class)).isEmpty();
        assertThat(context.getBeansOfType(EmailSignInController.class)).isEmpty();
        assertThat(context.getBeansOfType(EmailCodeMailer.class)).isEmpty();

        mvc.perform(get("/login/email")).andExpect(status().isNotFound());
        mvc.perform(get("/login/email/code")).andExpect(status().isNotFound());
        mvc.perform(post("/login/email").with(csrf()).param("email", "ana@example.ro"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/login/email/code").with(csrf()).param("code", "123456"))
                .andExpect(status().is4xxClientError());
        assertThat(mail.sent()).isEmpty();

        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/login/email"))));
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("code we email you"))));
        Fixture f = openFixture();
        scan(newBrowser(), f.sessionId(), validToken(f.sessionId()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("emailSignIn", false))
                .andExpect(content().string(not(containsString("/login/email"))));
    }
}
