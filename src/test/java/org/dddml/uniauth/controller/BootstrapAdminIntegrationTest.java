package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.bootstrapCsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.bootstrap-admin.enabled=true",
        "app.bootstrap-admin.username=admin",
        "app.bootstrap-admin.email=admin@example.invalid",
        "app.bootstrap-admin.password=Initial-admin-password",
        "app.bootstrap-admin.display-name=Initial Administrator"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BootstrapAdminIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Test
    void seededAdminLogsInByUsernameChangesPasswordAndInvalidatesOldToken()
            throws Exception {
        assertThat(loginMethodRepository.findByLocalUsername("admin"))
                .get()
                .extracting(UserLoginMethod::getLocalUsername)
                .isEqualTo("admin");

        MvcResult wrongPassword = login("admin", "wrong-password");
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);

        MvcResult initialLogin = login("admin", "Initial-admin-password");
        JsonNode initialBody = json(initialLogin);
        assertThat(initialBody.path("user").path("username").asText())
                .isEqualTo("admin");
        assertThat(initialBody.path("user").path("authorities").toString())
                .contains("ROLE_ADMIN");
        String accessToken = initialBody.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        String refreshToken =
                org.dddml.uniauth.support.AuthIntegrationTestSupport
                        .responseCookie(initialLogin, "refreshToken");
        Cookie accessCookie = initialLogin.getResponse().getCookie("accessToken");
        assertThat(accessCookie).isNotNull();

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value("admin"))
                .andExpect(jsonPath("$.hasLocalPassword").value(true));

        mockMvc.perform(put("/api/user/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "wrong-current-password",
                                  "newPassword": "A-stronger-admin-password-2026",
                                  "newPasswordConfirm": "A-stronger-admin-password-2026"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("CURRENT_PASSWORD_INVALID"));

        mockMvc.perform(put("/api/user/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "Initial-admin-password",
                                  "newPassword": "A-stronger-admin-password-2026",
                                  "newPasswordConfirm": "different-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("PASSWORD_CONFIRMATION_MISMATCH"));

        var csrf = bootstrapCsrf(mockMvc, objectMapper);
        mockMvc.perform(
                        org.dddml.uniauth.support.AuthIntegrationTestSupport
                                .withCsrf(
                                        put("/api/user/password")
                                                .cookie(accessCookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("""
                                                        {
                                                          "currentPassword": "Initial-admin-password",
                                                          "newPassword": "A-stronger-admin-password-2026",
                                                          "newPasswordConfirm": "A-stronger-admin-password-2026"
                                                        }
                                                        """),
                                        csrf
                                )
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0))
                .andExpect(cookie().maxAge("JSESSIONID", 0));

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        var refreshCsrf = bootstrapCsrf(mockMvc, objectMapper);
        mockMvc.perform(
                        org.dddml.uniauth.support.AuthIntegrationTestSupport.withCsrf(
                                post("/api/auth/refresh")
                                        .cookie(new Cookie("refreshToken", refreshToken)),
                                refreshCsrf
                        )
                )
                .andExpect(status().isUnauthorized());
        assertThat(login("admin", "Initial-admin-password")
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(login("admin", "A-stronger-admin-password-2026")
                .getResponse().getStatus()).isEqualTo(200);
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginPayload(username, password)
                        )))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsByteArray()
        );
    }

    private record LoginPayload(String username, String password) {
    }
}
