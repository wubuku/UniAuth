package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.dto.RegisterRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.frontend.url=https://frontend.example.test/console",
        "app.frontend.allowed-redirect-origins=https://alternate.example.test"
})
@ActiveProfiles("test")
class OAuth2SuccessHandlerIntegrationTest extends PostgreSqlIntegrationTest {

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
    private JwtTokenService jwtTokenService;

    @Autowired
    private OAuth2AuthorizationRequestResolver authorizationRequestResolver;

    @Autowired
    @Qualifier("oauth2FailureHandler")
    private AuthenticationFailureHandler failureHandler;

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
    void authenticatedCallbackBindsNewProviderToExistingUser() throws Exception {
        UserDto localUser = registerLocalUser("oauth-binding");
        String accessToken = jwtTokenService.generateAccessToken(
                localUser.getUsername(),
                localUser.getEmail(),
                localUser.getId(),
                localUser.getAuthorities()
        );
        String providerSubject = "github-binding-" + UUID.randomUUID();

        JsonNode response = executeJsonCallback(
                authentication("github", providerSubject),
                accessToken
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
        String secondUserToken = jwtTokenService.generateAccessToken(
                secondUser.getUsername(),
                secondUser.getEmail(),
                secondUser.getId(),
                secondUser.getAuthorities()
        );
        JsonNode conflictResponse = executeJsonCallback(
                authentication("github", providerSubject),
                secondUserToken
        );

        assertThat(conflictResponse.path("authenticated").asBoolean()).isFalse();
        assertThat(conflictResponse.path("error").asText())
                .isEqualTo("该OAuth2账户已被其他用户绑定");
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.GITHUB,
                providerSubject
        )).get().extracting(method -> method.getUser().getId()).isEqualTo(ownerId);
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
        String secondUserToken = jwtTokenService.generateAccessToken(
                secondUser.getUsername(),
                secondUser.getEmail(),
                secondUser.getId(),
                secondUser.getAuthorities()
        );
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
        String secondUserToken = jwtTokenService.generateAccessToken(
                secondUser.getUsername(),
                secondUser.getEmail(),
                secondUser.getId(),
                secondUser.getAuthorities()
        );
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
                .startsWith(
                        "https://alternate.example.test/oauth/complete"
                                + "?source=github&error="
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
    void authorizationRequestDoesNotPersistAnUntrustedRefererOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/oauth2/authorization/github"
        );
        request.addHeader("Referer", "https://evil.example/account");

        authorizationRequestResolver.resolve(request);

        assertThat(request.getSession(false))
                .satisfies(session -> {
                    if (session != null) {
                        assertThat(session.getAttribute("OAUTH2_FRONTEND_URL")).isNull();
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
