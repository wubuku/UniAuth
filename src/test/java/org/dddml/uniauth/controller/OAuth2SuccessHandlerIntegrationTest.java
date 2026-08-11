package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.dto.RegisterRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenSessionSnapshot;
import org.dddml.uniauth.service.TokenSessionTransactionService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.issueTokens;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.frontend.url=https://frontend.example.test/console",
        "app.frontend.allowed-redirect-origins=https://alternate.example.test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuth2SuccessHandlerIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String BINDING_SESSION_ATTRIBUTE =
            "UNIAUTH_OAUTH2_BINDING_PROVIDER";

    @Autowired
    @Qualifier("oauth2SuccessHandler")
    private AuthenticationSuccessHandler successHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private LoginMethodService loginMethodService;

    @Autowired
    private TokenSessionTransactionService tokenSessionTransactionService;

    @Autowired
    private TokenIssuanceFacade tokenIssuanceFacade;

    @Autowired
    private OAuth2AuthorizationRequestResolver authorizationRequestResolver;

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    @Qualifier("oauth2FailureHandler")
    private AuthenticationFailureHandler failureHandler;

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"google", "github", "x"})
    void providerCallbackCreatesStableAccountWithoutExternalProviderCalls(String provider)
            throws Exception {
        String subject = provider + "-" + UUID.randomUUID();
        OAuth2AuthenticationToken authentication = authentication(provider, subject);

        JsonNode firstResponse = executeJsonCallback(authentication, null);
        JsonNode secondResponse = executeJsonCallback(authentication, null);

        String userId = firstResponse.path("user").path("id").asText();
        assertThat(firstResponse.path("authenticated").asBoolean()).isTrue();
        assertThat(secondResponse.path("user").path("id").asText()).isEqualTo(userId);

        UserLoginMethod.AuthProvider expectedProvider = switch (provider) {
            case "google" -> UserLoginMethod.AuthProvider.GOOGLE;
            case "github" -> UserLoginMethod.AuthProvider.GITHUB;
            case "x" -> UserLoginMethod.AuthProvider.TWITTER;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                expectedProvider,
                subject
        )).get().satisfies(method -> {
            assertThat(method.getUser().getId()).isEqualTo(userId);
            assertThat(method.isPrimary()).isTrue();
        });
    }

    @Test
    void xRegistrationRequestsScopesRequiredByCurrentUserEndpoint() {
        var registration =
                clientRegistrationRepository.findByRegistrationId("x");

        assertThat(registration.getScopes())
                .containsExactly("users.read");
        assertThat(registration.getProviderDetails()
                .getUserInfoEndpoint()
                .getUri())
                .isEqualTo("https://api.x.com/2/users/me");
        assertThat(registration.getProviderDetails()
                .getUserInfoEndpoint()
                .getUserNameAttributeName())
                .isEqualTo("username");
    }

    @Test
    void accessCookieDoesNotTurnOrdinaryCallbackIntoBinding() throws Exception {
        UserDto localUser = registerLocalUser("oauth-binding");
        String accessToken = accessToken(localUser);
        String providerSubject = "github-binding-" + UUID.randomUUID();
        loginMethodService.bindOAuth2LoginMethod(
                localUser.getId(),
                UserLoginMethod.AuthProvider.GITHUB,
                providerSubject,
                providerSubject + "@example.invalid",
                "Existing GitHub Login"
        );

        JsonNode response = executeJsonCallback(
                authentication("github", providerSubject),
                accessToken
        );

        assertThat(response.path("message").asText()).isEqualTo("Login successful");
        assertThat(response.path("user").path("id").asText()).isEqualTo(localUser.getId());
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.GITHUB,
                providerSubject
        )).get().extracting(method -> method.getUser().getId())
                .isEqualTo(localUser.getId());
    }

    @Test
    void explicitBindingIntentBindsNewProviderToExistingUser() throws Exception {
        UserDto localUser = registerLocalUser("oauth-explicit-binding");
        String providerSubject = "github-explicit-binding-" + UUID.randomUUID();

        JsonNode response = executeJsonBindingCallback(
                authentication("github", providerSubject),
                accessToken(localUser),
                "github"
        );

        assertThat(response.path("message").asText()).isEqualTo("Binding successful");
        assertThat(response.path("user").path("id").asText()).isEqualTo(localUser.getId());
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.GITHUB,
                providerSubject
        )).get().extracting(method -> method.getUser().getId()).isEqualTo(localUser.getId());
    }

    @Test
    void callbackRejectsProviderAccountAlreadyBoundToAnotherUser() throws Exception {
        String providerSubject = "github-conflict-" + UUID.randomUUID();
        JsonNode ownerResponse = executeJsonCallback(
                authentication("github", providerSubject),
                null
        );
        String ownerId = ownerResponse.path("user").path("id").asText();

        UserDto secondUser = registerLocalUser("oauth-conflict-target");
        String secondUserToken = accessToken(secondUser);
        JsonNode conflictResponse = executeJsonBindingCallback(
                authentication("github", providerSubject),
                secondUserToken,
                "github"
        );

        assertThat(conflictResponse.path("authenticated").asBoolean()).isFalse();
        assertThat(conflictResponse.path("error").asText())
                .isEqualTo("oauth2_binding_conflict");
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.GITHUB,
                providerSubject
        )).get().extracting(method -> method.getUser().getId()).isEqualTo(ownerId);
    }

    @Test
    void bindingIntentCannotBeReplayed() throws Exception {
        UserDto localUser = registerLocalUser("oauth-bind-replay");
        String providerSubject = "github-bind-replay-" + UUID.randomUUID();
        BindingCallback binding = prepareBindingCallback(
                accessToken(localUser),
                "github"
        );

        JsonNode first = executeJsonCallback(
                authentication("github", providerSubject),
                binding.state(),
                binding.session()
        );
        JsonNode replay = executeJsonCallback(
                authentication("github", providerSubject),
                binding.state(),
                binding.session()
        );

        assertThat(first.path("message").asText()).isEqualTo("Binding successful");
        assertThat(replay.path("authenticated").asBoolean()).isFalse();
        assertThat(replay.path("error").asText()).isEqualTo("oauth2_processing_failed");
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.GITHUB,
                providerSubject
        )).get().extracting(method -> method.getUser().getId()).isEqualTo(localUser.getId());
    }

    @Test
    void bindingIntentRejectsSessionAndProviderMismatch() throws Exception {
        UserDto localUser = registerLocalUser("oauth-bind-boundaries");

        BindingCallback sessionBound = prepareBindingCallback(
                accessToken(localUser),
                "github"
        );
        JsonNode sessionMismatch = executeJsonCallback(
                authentication("github", "github-session-mismatch-" + UUID.randomUUID()),
                sessionBound.state(),
                new MockHttpSession()
        );

        BindingCallback providerBound = prepareBindingCallback(
                accessToken(localUser),
                "github"
        );
        JsonNode providerMismatch = executeJsonCallback(
                authentication("x", "x-provider-mismatch-" + UUID.randomUUID()),
                providerBound.state(),
                providerBound.session()
        );

        assertThat(sessionMismatch.path("error").asText())
                .isEqualTo("oauth2_processing_failed");
        assertThat(providerMismatch.path("error").asText())
                .isEqualTo("oauth2_processing_failed");
    }

    @Test
    void unknownProviderIsRejectedWithoutCreatingLoginMethod() throws Exception {
        long initialCount = loginMethodRepository.count();
        OAuth2User principal = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("name", "Unknown Provider User"),
                "name"
        );
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                "unknown"
        );

        JsonNode response = executeJsonCallback(authentication, null);

        assertThat(response.path("authenticated").asBoolean()).isFalse();
        assertThat(response.path("error").asText()).isNotBlank();
        assertThat(loginMethodRepository.count()).isEqualTo(initialCount);
    }

    @Test
    void redirectCallbackUsesTheConfiguredFrontendUrl() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request,
                response,
                authentication("github", "github-redirect-" + UUID.randomUUID())
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo("https://frontend.example.test/console/");
    }

    @Test
    void maliciousStateRedirectCannotEscapeTheConfiguredFrontend() throws Exception {
        String providerSubject = "github-redirect-conflict-" + UUID.randomUUID();
        executeJsonCallback(authentication("github", providerSubject), null);
        UserDto secondUser = registerLocalUser("oauth-redirect-conflict");
        String secondUserToken = accessToken(secondUser);
        String state = URLEncoder.encode(
                """
                {"redirect_uri":"https://evil.example/collect","response_type":"redirect"}
                """,
                StandardCharsets.UTF_8
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        request.setParameter("state", state);
        request.setCookies(new Cookie("accessToken", secondUserToken));
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request,
                response,
                authentication("github", providerSubject)
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .startsWith("https://frontend.example.test/console/login?error=")
                .doesNotContain("evil.example");
    }

    @Test
    void allowedStateRedirectKeepsItsPathAndExistingQuery() throws Exception {
        String providerSubject = "github-allowed-redirect-" + UUID.randomUUID();
        executeJsonCallback(authentication("github", providerSubject), null);
        UserDto secondUser = registerLocalUser("oauth-allowed-redirect");
        String secondUserToken = accessToken(secondUser);
        String state = URLEncoder.encode(
                """
                {
                  "redirect_uri":"https://alternate.example.test/oauth/complete?source=github",
                  "response_type":"redirect"
                }
                """,
                StandardCharsets.UTF_8
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        request.setParameter("state", state);
        request.setCookies(new Cookie("accessToken", secondUserToken));
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(
                request,
                response,
                authentication("github", providerSubject)
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo(
                        "https://frontend.example.test/console/login"
                                + "?error=oauth2_processing_failed"
                );
    }

    @Test
    void oauth2FailureHandlerUsesTheConfiguredFrontendLoginPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(
                request,
                response,
                new AuthenticationServiceException("provider rejected request")
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo(
                        "https://frontend.example.test/console/login?error=oauth2_failed"
                );
    }

    @Test
    void bindingOauth2FailureUsesBindingErrorAndClearsMarker() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        request.getSession(true).setAttribute(
                BINDING_SESSION_ATTRIBUTE,
                "github"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        failureHandler.onAuthenticationFailure(
                request,
                response,
                new AuthenticationServiceException("provider rejected binding")
        );

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl())
                .isEqualTo(
                        "https://frontend.example.test/console/login"
                                + "?error=oauth2_binding_failed"
                );
        assertThat(request.getSession(false).getAttribute(
                BINDING_SESSION_ATTRIBUTE)).isNull();
    }

    @Test
    void expiredRecentAuthenticationReturnsControlledBindingRejection()
            throws Exception {
        UserDto localUser = registerLocalUser("oauth-bind-stale-auth");
        TokenSessionSnapshot snapshot = tokenSessionTransactionService.create(
                localUser.getId(),
                Instant.now().minusSeconds(601),
                null
        );
        String accessToken = tokenIssuanceFacade.sign(snapshot).accessToken();

        mockMvc.perform(get("/oauth2/bind/github")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error")
                        .value("RECENT_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message")
                        .value("Recent authentication is required"));
    }

    @Test
    void ordinaryAuthorizationRequestClearsBindingMarkerAndRejectsUntrustedRefererOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/oauth2/authorization/github"
        );
        request.addHeader("Referer", "https://evil.example/account");
        request.getSession(true).setAttribute(
                BINDING_SESSION_ATTRIBUTE,
                "github"
        );

        authorizationRequestResolver.resolve(request);

        assertThat(request.getSession(false))
                .satisfies(session -> {
                    if (session != null) {
                        assertThat(session.getAttribute("OAUTH2_FRONTEND_URL")).isNull();
                        assertThat(session.getAttribute(
                                BINDING_SESSION_ATTRIBUTE))
                                .isNull();
                    }
                });
    }

    private JsonNode executeJsonCallback(
            OAuth2AuthenticationToken authentication,
            String accessToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        if (accessToken != null) {
            request.setCookies(new Cookie("accessToken", accessToken));
        }
        return executeJsonRequest(authentication, request);
    }

    private JsonNode executeJsonBindingCallback(
            OAuth2AuthenticationToken authentication,
            String accessToken,
            String provider) throws Exception {
        BindingCallback binding = prepareBindingCallback(accessToken, provider);
        return executeJsonCallback(
                authentication,
                binding.state(),
                binding.session()
        );
    }

    private BindingCallback prepareBindingCallback(
            String accessToken,
            String provider) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest authorizationRequest = new MockHttpServletRequest(
                "GET",
                "/oauth2/bind/" + provider
        );
        authorizationRequest.setServletPath("/oauth2/bind/" + provider);
        authorizationRequest.setSession(session);
        authorizationRequest.setCookies(new Cookie("accessToken", accessToken));
        OAuth2AuthorizationRequest resolved =
                authorizationRequestResolver.resolve(authorizationRequest);
        assertThat(resolved).isNotNull();
        assertThat(resolved.getState()).isNotBlank();
        return new BindingCallback(resolved.getState(), session);
    }

    private JsonNode executeJsonCallback(
            OAuth2AuthenticationToken authentication,
            String state,
            MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/oauth2/callback");
        request.addHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        request.setParameter("state", state);
        request.setSession(session);
        return executeJsonRequest(authentication, request);
    }

    private JsonNode executeJsonRequest(
            OAuth2AuthenticationToken authentication,
            MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode responseBody = objectMapper.readTree(response.getContentAsByteArray());
        if (responseBody.path("authenticated").asBoolean()) {
            assertThat(Arrays.stream(response.getCookies())
                    .map(Cookie::getName))
                    .contains("accessToken", "refreshToken");
            assertThat(Arrays.stream(response.getCookies())
                    .filter(cookie -> "accessToken".equals(cookie.getName())
                            || "refreshToken".equals(cookie.getName())))
                    .allSatisfy(cookie -> {
                        assertThat(cookie.isHttpOnly()).isTrue();
                        assertThat(cookie.getSecure()).isFalse();
                        assertThat(cookie.getPath()).isEqualTo("/");
                        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
                        assertThat(cookie.getMaxAge()).isEqualTo(
                                "accessToken".equals(cookie.getName()) ? 3600 : 604800
                        );
                    });
        }
        return responseBody;
    }

    private record BindingCallback(String state, MockHttpSession session) {
    }

    private String accessToken(UserDto user) {
        return issueTokens(
                tokenSessionTransactionService,
                tokenIssuanceFacade,
                user.getId()
        ).accessToken();
    }

    private OAuth2AuthenticationToken authentication(String provider, String subject) {
        if ("google".equals(provider)) {
            Instant issuedAt = Instant.now().minusSeconds(5);
            OidcIdToken idToken = new OidcIdToken(
                    "test-id-token",
                    issuedAt,
                    issuedAt.plusSeconds(300),
                    Map.of(
                            "sub", subject,
                            "email", subject + "@example.invalid",
                            "name", "Google Integration User",
                            "picture", "https://example.invalid/avatar.png"
                    )
            );
            DefaultOidcUser principal = new DefaultOidcUser(
                    Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                    idToken
            );
            return new OAuth2AuthenticationToken(
                    principal,
                    principal.getAuthorities(),
                    "google"
            );
        }

        Map<String, Object> attributes = "github".equals(provider)
                ? Map.of(
                        "id", subject,
                        "login", "github-" + subject,
                        "email", subject + "@example.invalid",
                        "avatar_url", "https://example.invalid/avatar.png"
                )
                : Map.of(
                        "id", subject,
                        "username", "x-" + subject,
                        "profile_image_url", "https://example.invalid/avatar.png"
                );
        String nameAttribute = "github".equals(provider) ? "login" : "username";
        DefaultOAuth2User principal = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                nameAttribute
        );
        return new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                provider
        );
    }

    private UserDto registerLocalUser(String prefix) {
        String suffix = UUID.randomUUID().toString();
        String username = prefix + "-" + suffix;
        return userService.register(new RegisterRequest(
                username,
                username + "@example.invalid",
                "integration-password",
                "OAuth Binding User",
                null,
                null
        ));
    }
}
