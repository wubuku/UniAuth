package org.dddml.uniauth.controller;

import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiSecurityIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowlistedRoutesReachControllers() throws Exception {
        mockMvc.perform(post("/api/auth/check-verification-code")
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/auth/web3/nonce/not-a-wallet"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAndRemovedAuthRoutesAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/auth/check-user"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/auth/user"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/create-test-user"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/auth/web3/nonce/0x0000000000000000000000000000000000000000"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/auth/not-allowlisted"))
                .andExpect(status().isForbidden());
    }

    @Test
    void removedOauthDiagnosticRoutesAreNotMapped() throws Exception {
        mockMvc.perform(get("/oauth2/introspect-test"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/oauth2/validate"))
                .andExpect(status().isNotFound());
    }

    @Test
    void canonicalCurrentUserRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/user"))
                .andExpect(status().isUnauthorized());
    }

}
