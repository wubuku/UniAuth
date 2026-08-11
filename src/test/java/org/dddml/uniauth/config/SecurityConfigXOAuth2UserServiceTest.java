package org.dddml.uniauth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(OutputCaptureExtension.class)
class SecurityConfigXOAuth2UserServiceTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> userService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        SecurityConfig config = new SecurityConfig();
        ReflectionTestUtils.setField(
                config,
                "oauth2RestTemplate",
                restTemplate
        );
        userService = config.oauth2UserService();
    }

    @Test
    void xUserInfoFailureBecomesOAuthAuthenticationFailure(
            CapturedOutput output) {
        server.expect(requestTo(
                        "https://api.x.com/2/users/me"
                                + "?user.fields=id,username,profile_image_url"))
                .andExpect(request -> assertThat(request.getMethod())
                        .isEqualTo(HttpMethod.GET))
                .andExpect(request -> assertThat(
                        request.getHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer x-access-token"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "type": "https://api.x.com/2/problems/not-authorized-for-resource",
                                  "title": "Forbidden",
                                  "status": 403,
                                  "detail": "Scope is not authorized"
                                }
                                """));

        assertThatThrownBy(() -> userService.loadUser(xUserRequest()))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception -> assertThat(
                                exception.getError().getErrorCode())
                                .isEqualTo("invalid_user_info_response")
                );
        assertThat(output)
                .containsPattern(
                        "configuredScopes=\\[(tweet\\.read, users\\.read"
                                + "|users\\.read, tweet\\.read)\\]"
                )
                .containsPattern(
                        "grantedScopes=\\[(tweet\\.read, users\\.read"
                                + "|users\\.read, tweet\\.read)\\]"
                )
                .contains(
                        "type=https://api.x.com/2/problems/"
                                + "not-authorized-for-resource")
                .contains("title=Forbidden")
                .contains("detail=Scope is not authorized")
                .doesNotContain("x-access-token");
        server.verify();
    }

    @Test
    void xUserInfoResponseUsesUsernameAsPrincipalName() {
        server.expect(requestTo(
                        "https://api.x.com/2/users/me"
                                + "?user.fields=id,username,profile_image_url"))
                .andExpect(request -> assertThat(
                        request.getHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer x-access-token"))
                .andRespond(withSuccess("""
                        {
                          "data": {
                            "id": "x-user-1",
                            "username": "circle_user",
                            "profile_image_url": "https://img.example/x.png",
                            "description": "must not be retained"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OAuth2User user = userService.loadUser(xUserRequest());

        assertThat(user.getName()).isEqualTo("circle_user");
        assertThat((String) user.getAttribute("id")).isEqualTo("x-user-1");
        assertThat((String) user.getAttribute("profile_image_url"))
                .isEqualTo("https://img.example/x.png");
        assertThat(user.getAttributes()).containsOnlyKeys(
                "id",
                "username",
                "profile_image_url");
        server.verify();
    }

    @Test
    void xUserInfoRejectsMissingId() {
        expectXResponse("""
                {"data":{"username":"circle_user"}}
                """);

        assertThatThrownBy(() -> userService.loadUser(xUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    @Test
    void xUserInfoRejectsMissingUsername() {
        expectXResponse("""
                {"data":{"id":"x-user-1"}}
                """);

        assertThatThrownBy(() -> userService.loadUser(xUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    @Test
    void xUserInfoRejectsNonObjectData() {
        expectXResponse("""
                {"data":[]}
                """);

        assertThatThrownBy(() -> userService.loadUser(xUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    private void expectXResponse(String body) {
        server.expect(requestTo(
                        "https://api.x.com/2/users/me"
                                + "?user.fields=id,username,profile_image_url"))
                .andExpect(request -> assertThat(
                        request.getHeaders().getFirst("Authorization"))
                        .isEqualTo("Bearer x-access-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private OAuth2UserRequest xUserRequest() {
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("x")
                        .clientId("x-client")
                        .clientSecret("x-secret")
                        .authorizationGrantType(
                                AuthorizationGrantType.AUTHORIZATION_CODE
                        )
                        .redirectUri("https://circle.example/oauth2/callback")
                        .scope("tweet.read", "users.read")
                        .authorizationUri(
                                "https://x.com/i/oauth2/authorize"
                        )
                        .tokenUri("https://api.x.com/2/oauth2/token")
                        .userInfoUri("https://api.x.com/2/users/me")
                        .userNameAttributeName("username")
                        .clientName("X")
                        .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "x-access-token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Set.of("tweet.read", "users.read")
        );
        return new OAuth2UserRequest(registration, accessToken);
    }
}
