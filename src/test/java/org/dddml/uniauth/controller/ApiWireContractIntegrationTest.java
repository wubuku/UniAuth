package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenSessionTransactionService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.issueTokens;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the JSON surface that the SPA depends on. The field lists live in
 * {@code contracts/api-wire-contract.json}, which is also asserted against
 * {@code frontend/src/types/index.ts} by {@code frontend/tests/unit/wire-contract.test.ts}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiWireContractIntegrationTest extends PostgreSqlIntegrationTest {

    private static final Path CONTRACT_PATH =
            Path.of("contracts", "api-wire-contract.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenSessionTransactionService tokenSessionTransactionService;

    @Autowired
    private TokenIssuanceFacade tokenIssuanceFacade;

    @Test
    void currentUserResponseMatchesTheWireContract() throws Exception {
        String userId = registerLocalUser(
                "wire-contract-user",
                "wire-contract-user@example.invalid",
                "wire-contract-password"
        );
        String accessToken = issueTokens(
                tokenSessionTransactionService,
                tokenIssuanceFacade,
                userId
        ).accessToken();

        MvcResult result = mockMvc.perform(get("/api/user")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken
                        ))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = responseJson(result);
        Set<String> expected = contractFields("GET /api/user", "requiredFields");
        assertThat(fieldNames(body)).isEqualTo(expected);
        assertThat(body.path("authenticated").asBoolean()).isTrue();
        assertThat(body.path("userId").asText()).isEqualTo(userId);
        assertThat(body.path("userName").asText()).isEqualTo("wire-contract-user");
        assertThat(body.path("userEmail").asText())
                .isEqualTo("wire-contract-user@example.invalid");
        assertThat(body.path("hasLocalPassword").asBoolean()).isTrue();
        assertThat(body.path("providerInfo").isObject()).isTrue();
    }

    @Test
    void loginResponseMatchesTheWireContract() throws Exception {
        String username = "wire-contract-login";
        String userId = registerLocalUser(
                username,
                "wire-contract-login@example.invalid",
                "wire-contract-password"
        );

        MvcResult result = login(username, "wire-contract-password");
        JsonNode body = responseJson(result);
        Set<String> allowed = contractFields(
                "POST /api/auth/login",
                "requiredFields"
        );
        allowed.addAll(contractFields("POST /api/auth/login", "optionalFields"));
        assertThat(fieldNames(body)).isSubsetOf(allowed);
        assertThat(fieldNames(body)).containsAll(
                contractFields("POST /api/auth/login", "requiredFields")
        );
        assertThat(body.path("authenticated").asBoolean()).isTrue();
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("accessTokenExpiresIn").isNumber()).isTrue();

        Set<String> expectedUserFields = contractFields(
                "POST /api/auth/login",
                "userFields"
        );
        assertThat(fieldNames(body.path("user"))).isEqualTo(expectedUserFields);
        assertThat(body.path("user").path("id").asText()).isEqualTo(userId);
        assertThat(body.path("user").path("username").asText()).isEqualTo(username);
    }

    @Test
    void refreshResponseAndRejectionMatchTheWireContract() throws Exception {
        String username = "wire-contract-refresh";
        registerLocalUser(
                username,
                "wire-contract-refresh@example.invalid",
                "wire-contract-password"
        );
        MvcResult loginResult = login(username, "wire-contract-password");
        String refreshToken = loginResult.getResponse()
                .getCookie("refreshToken")
                .getValue();

        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(
                                "refreshToken",
                                refreshToken
                        ))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode refreshBody = responseJson(refreshResult);
        Set<String> allowedRefreshFields = contractFields(
                "POST /api/auth/refresh",
                "requiredFields"
        );
        allowedRefreshFields.addAll(contractFields(
                "POST /api/auth/refresh",
                "optionalFields"
        ));
        assertThat(fieldNames(refreshBody)).isSubsetOf(allowedRefreshFields);
        assertThat(refreshBody.path("message").asText())
                .isEqualTo("Token refreshed successfully");
        assertThat(refreshBody.path("tokenType").asText()).isEqualTo("Bearer");

        JsonNode rejectionContract = contract()
                .path("endpoints")
                .path("errorBodies")
                .path("refreshCookieMissing");
        MvcResult rejection = mockMvc.perform(post("/api/auth/refresh")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(rejection.getResponse().getStatus())
                .isEqualTo(rejectionContract.path("status").asInt());
        assertThat(responseJson(rejection))
                .isEqualTo(rejectionContract.path("body"));
    }

    private Set<String> contractFields(String endpoint, String fieldList)
            throws Exception {
        Set<String> fields = new HashSet<>();
        contract().path("endpoints")
                .path(endpoint)
                .path(fieldList)
                .forEach(node -> fields.add(node.asText()));
        return fields;
    }

    private JsonNode contract() throws Exception {
        assertThat(Files.exists(CONTRACT_PATH)).isTrue();
        return objectMapper.readTree(Files.readString(CONTRACT_PATH));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> iterator = node.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private MvcResult login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of(
                                        "username", username,
                                        "password", password
                                )
                        )))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String registerLocalUser(String username, String email, String password) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName("Wire Contract User");
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
}
