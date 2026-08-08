package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationFlowIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private LoginMethodService loginMethodService;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void localRegistrationLoginRefreshAndProtectedApisWorkAcrossHttpAndPostgreSql()
            throws Exception {
        String username = "integration-local";
        String password = "integration-password";
        String userId = registerLocalUser(
                username,
                "integration-local@example.invalid",
                password
        );

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "integration-local",
                                  "email": "other@example.invalid",
                                  "password": "different-password",
                                  "displayName": "Duplicate"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Username already exists"));

        mockMvc.perform(post("/api/auth/login")
                        .param("username", username)
                        .param("password", "wrong-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(cookie().httpOnly("accessToken", true))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andReturn();

        JsonNode loginBody = responseJson(loginResult);
        assertTokenCookies(loginResult, false);
        String accessToken = loginBody.path("accessToken").asText();
        String refreshToken = loginBody.path("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank().isNotEqualTo(accessToken);

        UserLoginMethod localMethod = loginMethodRepository
                .findByLocalUsername(username)
                .orElseThrow();
        assertThat(localMethod.getLastUsedAt()).isNotNull();

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.userName").value(username));

        mockMvc.perform(get("/api/user/login-methods")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.loginMethods[0].authProvider").value("local"))
                .andExpect(jsonPath("$.loginMethods[0].isPrimary").value(true));

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.aud").value("resource-server"));

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").value("key-1"));

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(cookie().httpOnly("accessToken", true))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andReturn();

        JsonNode refreshBody = responseJson(refreshResult);
        assertTokenCookies(refreshResult, false);
        assertThat(refreshBody.path("accessToken").asText()).isNotEqualTo(accessToken);
        assertThat(refreshBody.path("refreshToken").asText()).isNotEqualTo(refreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", accessToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());

        MvcResult logoutResult = mockMvc.perform(post("/api/auth/logout")
                        .cookie(
                                new Cookie("accessToken", accessToken),
                                new Cookie("refreshToken", refreshToken)
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0))
                .andReturn();
        assertClearedTokenCookies(logoutResult, false);
    }

    @Test
    void loginMethodManagementPreservesOwnershipAndLastMethodRules() throws Exception {
        String username = "integration-multi";
        String password = "integration-password";
        String userId = registerLocalUser(
                username,
                "integration-multi@example.invalid",
                password
        );
        String accessToken = login(username, password).path("accessToken").asText();
        String localMethodId = loginMethodRepository
                .findByLocalUsername(username)
                .orElseThrow()
                .getId();

        UserLoginMethod githubMethod = loginMethodService.bindOAuth2LoginMethod(
                userId,
                UserLoginMethod.AuthProvider.GITHUB,
                "github-integration-multi",
                "integration-multi@example.invalid",
                "Integration Multi"
        );

        mockMvc.perform(get("/api/user/login-methods")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));

        mockMvc.perform(put("/api/user/login-methods/{id}/primary", githubMethod.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryMethodId").value(githubMethod.getId()));

        assertThat(loginMethodRepository.findByUserIdAndIsPrimary(userId, true))
                .get()
                .extracting(UserLoginMethod::getId)
                .isEqualTo(githubMethod.getId());

        mockMvc.perform(delete("/api/user/login-methods/{id}", localMethodId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedMethodId").value(localMethodId));

        mockMvc.perform(delete("/api/user/login-methods/{id}", githubMethod.getId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("不能移除最后一个登录方式"));

        String otherUserId = registerLocalUser(
                "integration-other",
                "integration-other@example.invalid",
                password
        );
        String otherMethodId = loginMethodRepository
                .findByUserId(otherUserId)
                .get(0)
                .getId();
        mockMvc.perform(delete("/api/user/login-methods/{id}", otherMethodId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("无权移除该登录方式"));
    }

    @Test
    void oauthOnlyAccountCanAddOneLocalLoginMethodThroughProtectedHttpApi()
            throws Exception {
        UserDto oauthUser = userService.getOrCreateOAuthUser(
                "GITHUB",
                "github-oauth-only",
                "oauth-only@example.invalid",
                "OAuth Only",
                null
        );
        String accessToken = jwtTokenService.generateAccessToken(
                oauthUser.getUsername(),
                oauthUser.getEmail(),
                oauthUser.getId(),
                Set.of("ROLE_USER")
        );

        mockMvc.perform(post("/api/user/login-methods/add-local-login")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "oauth-local",
                                  "password": "oauth-local-password",
                                  "passwordConfirm": "oauth-local-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginMethod.authProvider").value("local"))
                .andExpect(jsonPath("$.loginMethod.localUsername").value("oauth-local"));

        mockMvc.perform(post("/api/auth/login")
                        .param("username", "oauth-local")
                        .param("password", "oauth-local-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(oauthUser.getId()));

        mockMvc.perform(post("/api/user/login-methods/add-local-login")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "oauth-local-2",
                                  "password": "oauth-local-password",
                                  "passwordConfirm": "oauth-local-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("该用户已有本地登录方式，无法重复添加"));
    }

    private String registerLocalUser(String username, String email, String password)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterPayload(
                                username,
                                email,
                                password,
                                "Integration User"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.provider").value("local"))
                .andReturn();
        return responseJson(result).path("id").asText();
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        return responseJson(result);
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertTokenCookies(MvcResult result, boolean secure) {
        assertCookie(result, "accessToken", 3600, secure);
        assertCookie(result, "refreshToken", 604800, secure);
    }

    private void assertClearedTokenCookies(MvcResult result, boolean secure) {
        assertCookie(result, "accessToken", 0, secure);
        assertCookie(result, "refreshToken", 0, secure);
    }

    private void assertCookie(
            MvcResult result,
            String name,
            int maxAge,
            boolean secure) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as(name).isNotNull();
        assertThat(cookie.isHttpOnly()).as(name + " HttpOnly").isTrue();
        assertThat(cookie.getSecure()).as(name + " Secure").isEqualTo(secure);
        assertThat(cookie.getPath()).as(name + " Path").isEqualTo("/");
        assertThat(cookie.getMaxAge()).as(name + " Max-Age").isEqualTo(maxAge);
        assertThat(cookie.getAttribute("SameSite"))
                .as(name + " SameSite")
                .isEqualTo("Lax");
    }

    private record RegisterPayload(
            String username,
            String email,
            String password,
            String displayName
    ) {
    }
}
