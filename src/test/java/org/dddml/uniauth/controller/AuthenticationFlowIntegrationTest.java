package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.config.IntrospectionProperties;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenSessionTransactionService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.support.AuthIntegrationTestSupport.CsrfContext;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.authenticatedIntrospection;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.basicAuthorization;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.bootstrapCsrf;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.issueTokens;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.responseCookie;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.withCsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    private UserRepository userRepository;

    @Autowired
    private LoginMethodService loginMethodService;

    @Autowired
    private UserService userService;

    @Autowired
    private TokenSessionTransactionService tokenSessionTransactionService;

    @Autowired
    private TokenIssuanceFacade tokenIssuanceFacade;

    @Autowired
    private IntrospectionProperties introspectionProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireEmailVerification").value(true));
        assertThat(userRepository.findByEmail("other@example.invalid")).isEmpty();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                username,
                                "wrong-password"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                username,
                                password
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("accessToken", true))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andReturn();

        JsonNode loginBody = responseJson(loginResult);
        assertTokenCookies(loginResult, false);
        String accessToken = loginBody.path("accessToken").asText();
        String refreshToken = responseCookie(loginResult, "refreshToken");
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank().isNotEqualTo(accessToken);
        CsrfContext csrf = bootstrapCsrf(mockMvc, objectMapper);

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

        mockMvc.perform(authenticatedIntrospection(
                        post("/oauth2/introspect")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("token", accessToken),
                        introspectionProperties
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.aud").value("resource-server"));

        mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").value("key-1"));

        MvcResult refreshResult = mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie("refreshToken", refreshToken)),
                        csrf
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(cookie().httpOnly("accessToken", true))
                .andExpect(cookie().httpOnly("refreshToken", true))
                .andReturn();

        JsonNode refreshBody = responseJson(refreshResult);
        assertTokenCookies(refreshResult, false);
        assertThat(refreshBody.path("accessToken").asText()).isNotEqualTo(accessToken);
        assertThat(responseCookie(refreshResult, "refreshToken"))
                .isNotEqualTo(refreshToken);

        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie("refreshToken", accessToken)),
                        csrf
                ))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());

        MvcResult logoutResult = mockMvc.perform(withCsrf(
                        post("/api/auth/logout")
                                .cookie(
                                        new Cookie("accessToken", accessToken),
                                        new Cookie("refreshToken", refreshToken)
                                ),
                        csrf
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
        String accessToken = login(username, password).path("accessToken").asText();

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

        String renewedAccessToken = issueAccessToken(userId);
        mockMvc.perform(delete("/api/user/login-methods/{id}", githubMethod.getId())
                        .header("Authorization", "Bearer " + renewedAccessToken))
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
                        .header("Authorization", "Bearer " + renewedAccessToken))
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
        String accessToken = issueAccessToken(oauthUser.getId());

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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                "oauth-local",
                                "oauth-local-password"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(oauthUser.getId()));

        String renewedAccessToken = login(
                "oauth-local",
                "oauth-local-password"
        ).path("accessToken").asText();
        mockMvc.perform(post("/api/user/login-methods/add-local-login")
                        .header("Authorization", "Bearer " + renewedAccessToken)
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

    @Test
    void introspectionRequiresOneBasicCredentialAndOneFormToken()
            throws Exception {
        String userId = registerLocalUser(
                "introspection-boundary",
                "introspection-boundary@example.invalid",
                "integration-password"
        );
        String accessToken = issueAccessToken(userId);
        String authorization = basicAuthorization(introspectionProperties);

        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Basic realm=\"token-introspection\""
                ))
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(post("/oauth2/introspect")
                        .header(HttpHeaders.AUTHORIZATION, "Basic d3Jvbmc6d3Jvbmc=")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/oauth2/introspect")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                authorization,
                                authorization
                        )
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/oauth2/introspect?token=" + accessToken)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/oauth2/introspect")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken, accessToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/oauth2/introspect")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(HttpHeaders.COOKIE, "accessToken=" + accessToken)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/oauth2/introspect")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.sub").value(userId))
                .andExpect(jsonPath("$.sid").isNotEmpty())
                .andExpect(jsonPath("$.generation").value(0))
                .andExpect(jsonPath("$.ver").value(0))
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.authorities").doesNotExist())
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        "no-store, no-cache, must-revalidate"
                ))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"));
    }

    @Test
    void loginRejectsReplacingAnotherUsersActiveBrowserSession()
            throws Exception {
        String password = "integration-password";
        String firstUserId = registerLocalUser(
                "session-owner",
                "session-owner@example.invalid",
                password
        );
        registerLocalUser(
                "session-target",
                "session-target@example.invalid",
                password
        );

        MvcResult firstLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                "session-owner",
                                password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String firstAccessToken = responseJson(firstLogin)
                .path("accessToken")
                .asText();
        String firstRefreshToken = responseCookie(firstLogin, "refreshToken");
        CsrfContext csrf = bootstrapCsrf(mockMvc, objectMapper);

        mockMvc.perform(withCsrf(
                        post("/api/auth/login")
                                .cookie(
                                        new Cookie(
                                                "accessToken",
                                                firstAccessToken
                                        ),
                                        new Cookie(
                                                "refreshToken",
                                                firstRefreshToken
                                        )
                                )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(
                                        new LoginPayload(
                                                "session-target",
                                                password
                                        )
                                )),
                        csrf
                ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("ACTIVE_SESSION_CONFLICT"));

        mockMvc.perform(get("/api/user")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + firstAccessToken
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(firstUserId));
    }

    private String issueAccessToken(String userId) {
        return issueTokens(
                tokenSessionTransactionService,
                tokenIssuanceFacade,
                userId
        ).accessToken();
    }

    private String registerLocalUser(String username, String email, String password)
            throws Exception {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName("Integration User");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(Set.of("ROLE_USER"));

        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(username)
                .localPasswordHash(passwordEncoder.encode(password))
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);
        return userRepository.saveAndFlush(user).getId();
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginPayload(
                                username,
                                password
                        ))))
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

    private record LoginPayload(String username, String password) {
    }
}
