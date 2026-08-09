package org.dddml.uniauth.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.AuthenticationCredentialResolver;
import org.dddml.uniauth.service.OAuth2BindingIntentService;
import org.dddml.uniauth.service.RecentAuthenticationService;
import org.dddml.uniauth.service.TokenValidationService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@RequiredArgsConstructor
public class ExplicitOAuth2AuthorizationRequestResolver
        implements OAuth2AuthorizationRequestResolver {

    private static final String LOGIN_BASE_URI = "/oauth2/authorization";
    private static final String BIND_BASE_URI = "/oauth2/bind";

    private final DefaultOAuth2AuthorizationRequestResolver loginResolver;
    private final DefaultOAuth2AuthorizationRequestResolver bindResolver;
    private final AuthenticationCredentialResolver credentialResolver;
    private final TokenValidationService tokenValidationService;
    private final RecentAuthenticationService recentAuthenticationService;
    private final OAuth2BindingIntentService bindingIntentService;
    private final AuthRateLimiter authRateLimiter;

    public static ExplicitOAuth2AuthorizationRequestResolver create(
            ClientRegistrationRepository registrations,
            AuthenticationCredentialResolver credentialResolver,
            TokenValidationService tokenValidationService,
            RecentAuthenticationService recentAuthenticationService,
            OAuth2BindingIntentService bindingIntentService,
            AuthRateLimiter authRateLimiter) {
        DefaultOAuth2AuthorizationRequestResolver login =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations,
                        LOGIN_BASE_URI
                );
        DefaultOAuth2AuthorizationRequestResolver bind =
                new DefaultOAuth2AuthorizationRequestResolver(
                        registrations,
                        BIND_BASE_URI
                );
        login.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce()
        );
        bind.setAuthorizationRequestCustomizer(
                OAuth2AuthorizationRequestCustomizers.withPkce()
        );
        return new ExplicitOAuth2AuthorizationRequestResolver(
                login,
                bind,
                credentialResolver,
                tokenValidationService,
                recentAuthenticationService,
                bindingIntentService,
                authRateLimiter
        );
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        boolean binding = isBindingRequest(request);
        OAuth2AuthorizationRequest authorizationRequest = binding
                ? bindResolver.resolve(request)
                : loginResolver.resolve(request);
        return finish(request, authorizationRequest, binding);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(
            HttpServletRequest request,
            String clientRegistrationId) {
        boolean binding = isBindingRequest(request);
        OAuth2AuthorizationRequest authorizationRequest = binding
                ? bindResolver.resolve(request, clientRegistrationId)
                : loginResolver.resolve(request, clientRegistrationId);
        return finish(request, authorizationRequest, binding);
    }

    private OAuth2AuthorizationRequest finish(
            HttpServletRequest request,
            OAuth2AuthorizationRequest authorizationRequest,
            boolean binding) {
        if (!binding || authorizationRequest == null) {
            return authorizationRequest;
        }
        String registrationId = registrationId(request);
        try {
            String tokenValue = credentialResolver.resolveAccessToken(request)
                    .orElseThrow();
            TokenValidationService.ValidatedToken token =
                    tokenValidationService.validatedAccessToken(tokenValue);
            recentAuthenticationService.requireRecent(token);
            authRateLimiter.requireAllowed(
                    AuthRateLimiter.Policy.OAUTH_AUTHORIZE,
                    request.getRemoteAddr(),
                    token.userId() + "|" + registrationId
            );
            bindingIntentService.create(
                    authorizationRequest.getState(),
                    request.getSession(true).getId(),
                    registrationId,
                    token
            );
            return authorizationRequest;
        } catch (RuntimeException exception) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(
                            "oauth2_binding_rejected",
                            "OAuth2 binding request was rejected",
                            null
                    ),
                    exception
            );
        }
    }

    private boolean isBindingRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(
                request.getContextPath().length()
        );
        return path.equals(BIND_BASE_URI)
                || path.startsWith(BIND_BASE_URI + "/");
    }

    private String registrationId(HttpServletRequest request) {
        String path = request.getRequestURI().substring(
                request.getContextPath().length()
        );
        int separator = path.lastIndexOf('/');
        if (separator < 0 || separator == path.length() - 1) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_request")
            );
        }
        String registrationId = path.substring(separator + 1);
        if (!registrationId.matches("google|github|x")) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_request")
            );
        }
        return registrationId;
    }
}
