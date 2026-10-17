package io.github.sanducc.attendance.config;

import io.github.sanducc.attendance.checkin.CheckInService;
import io.github.sanducc.attendance.checkin.PendingCheckIn;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

/**
 * Rule 7 convenience: when the browser holds a scan of a course with a Workspace domain lock, Google's sign-in is
 * asked to show only accounts of that domain ({@code hd=university.ro}, or {@code hd=*} = any Workspace account when
 * the course accepts several) and always to offer the account chooser ({@code prompt=select_account}), so a student
 * who is signed in to Google with a personal account can pick the school one.
 *
 * <p>Only a hint: anyone can edit the URL. The check that counts is the ID token's {@code hd} claim in
 * {@link CheckInService#checkIn}.
 */
@Component
public class WorkspaceDomainHintResolver implements OAuth2AuthorizationRequestResolver {

    static final String HOSTED_DOMAIN_PARAM = "hd";
    static final String PROMPT_PARAM = "prompt";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final CheckInService checkIns;

    public WorkspaceDomainHintResolver(ClientRegistrationRepository clientRegistrations, CheckInService checkIns) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(clientRegistrations,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        this.checkIns = checkIns;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withDomainHint(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withDomainHint(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest withDomainHint(HttpServletRequest request,
                                                      OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null
                || !"google".equals(authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID))) {
            return authorizationRequest;
        }
        PendingCheckIn pending = PendingCheckIn.from(request.getSession(false));
        if (pending == null) {
            return authorizationRequest;
        }
        List<String> domains = checkIns.rules(pending.sessionId()).allowedDomains();
        if (domains.isEmpty()) {
            return authorizationRequest;
        }
        String hint = domains.size() == 1 ? domains.getFirst() : "*";
        // from() leaves the request URI unset, so build() recomputes it with the extra parameters.
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(params -> {
                    params.put(HOSTED_DOMAIN_PARAM, hint);
                    params.put(PROMPT_PARAM, "select_account");
                })
                .build();
    }
}
