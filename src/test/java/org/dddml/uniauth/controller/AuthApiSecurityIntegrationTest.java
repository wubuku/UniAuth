package org.dddml.uniauth.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AuthApiSecurityIntegrationTest {

    private static final Path TEST_DIRECTORY = createTestDirectory();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void isolatedRuntimeProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DIRECTORY.resolve("uniauth-test.db")
        );
        registry.add("spring.session.store-type", () -> "none");
        registry.add(
                "jwt.rsa.key-file",
                () -> TEST_DIRECTORY.resolve("signing-key.ser").toString()
        );
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-google");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-google-secret");
        registry.add("spring.security.oauth2.client.registration.github.client-id", () -> "test-github");
        registry.add("spring.security.oauth2.client.registration.github.client-secret", () -> "test-github-secret");
        registry.add("spring.security.oauth2.client.registration.x.client-id", () -> "test-x");
        registry.add("spring.security.oauth2.client.registration.x.client-secret", () -> "test-x-secret");
    }

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

    @AfterAll
    static void removeTemporaryFiles() throws IOException {
        try (var paths = Files.walk(TEST_DIRECTORY)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to clean integration-test files", e);
                }
            });
        }
    }

    private static Path createTestDirectory() {
        try {
            return Files.createTempDirectory("uniauth-auth-api-test-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
