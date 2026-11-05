package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenSessionSnapshot;
import org.dddml.uniauth.service.TokenSessionTransactionService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers password-change invariants that the bootstrap-admin happy path does
 * not exercise: recent authentication, accounts without a local password,
 * and the PostgreSQL compare-and-set race between concurrent changes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordChangeSecurityIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String DELAY_TRIGGER = "test_delay_password_change_race";
    private static final String DELAY_FUNCTION = "test_delay_password_change_race";
    private static final String DELAY_SECONDS = "1.5";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenSessionTransactionService tokenSessionTransactionService;

    @Autowired
    private TokenIssuanceFacade tokenIssuanceFacade;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void removeDelayTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS " + DELAY_TRIGGER + " ON user_login_methods"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + DELAY_FUNCTION + "()");
    }

    @Test
    void staleAuthenticationIsRejectedWithoutMutatingPasswordOrSession()
            throws Exception {
        UserEntity user = createLocalPasswordUser("password-stale-auth");
        String accessToken = issueAccessToken(
                user.getId(),
                Instant.now().minusSeconds(601)
        );

        mockMvc.perform(put("/api/user/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(
                                "Integration-password-2026",
                                "Replacement-password-2026"
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("RECENT_AUTH_REQUIRED"));

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName").value(user.getUsername()));

        assertThat(loginStatus(user.getUsername(), "Integration-password-2026"))
                .isEqualTo(200);
        assertThat(loginStatus(user.getUsername(), "Replacement-password-2026"))
                .isEqualTo(401);
        assertThat(loginMethodRepository
                .findByUserIdAndAuthProvider(
                        user.getId(),
                        UserLoginMethod.AuthProvider.LOCAL
                ))
                .get()
                .extracting(UserLoginMethod::getLocalPasswordHash)
                .isNotNull();
    }

    @Test
    void localMethodWithoutPasswordReportsMissingLocalPassword()
            throws Exception {
        UserEntity user = createUserWithMethod(
                "password-without-hash",
                UserLoginMethod.AuthProvider.LOCAL,
                null
        );
        String accessToken = issueAccessToken(user.getId(), Instant.now());

        mockMvc.perform(put("/api/user/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(
                                "Integration-password-2026",
                                "Replacement-password-2026"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("LOCAL_PASSWORD_NOT_CONFIGURED"));

        assertThat(loginMethodRepository
                .findByUserIdAndAuthProvider(
                        user.getId(),
                        UserLoginMethod.AuthProvider.LOCAL
                ))
                .get()
                .extracting(UserLoginMethod::getLocalPasswordHash)
                .isNull();
    }

    @Test
    void accountWithoutLocalMethodReportsMissingLocalPassword()
            throws Exception {
        UserEntity user = createUserWithMethod(
                "password-github-only",
                UserLoginMethod.AuthProvider.GITHUB,
                null
        );
        String accessToken = issueAccessToken(user.getId(), Instant.now());

        mockMvc.perform(put("/api/user/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(
                                "Integration-password-2026",
                                "Replacement-password-2026"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("LOCAL_PASSWORD_NOT_CONFIGURED"));

        assertThat(loginMethodRepository.findByUserId(user.getId()))
                .hasSize(1)
                .allSatisfy(method -> assertThat(method.getAuthProvider())
                        .isEqualTo(UserLoginMethod.AuthProvider.GITHUB));
    }

    @Test
    void concurrentChangesAllowExactlyOneWinner() throws Exception {
        UserEntity user = createLocalPasswordUser("password-race");
        String accessToken = issueAccessToken(user.getId(), Instant.now());
        String firstNewPassword = "First-winner-password-2026";
        String secondNewPassword = "Second-winner-password-2026";

        installUpdateDelayTrigger();

        var executor = Executors.newFixedThreadPool(2);
        MvcResult first;
        MvcResult second;
        try {
            Callable<MvcResult> firstTask =
                    () -> performChange(accessToken, firstNewPassword);
            Future<MvcResult> firstFuture = executor.submit(firstTask);
            awaitDelayedUpdateInProgress();
            Callable<MvcResult> secondTask =
                    () -> performChange(accessToken, secondNewPassword);
            Future<MvcResult> secondFuture = executor.submit(secondTask);
            first = firstFuture.get(20, TimeUnit.SECONDS);
            second = secondFuture.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        List<Integer> statuses = List.of(
                first.getResponse().getStatus(),
                second.getResponse().getStatus()
        );
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        MvcResult conflict = statuses.get(0) == 409 ? first : second;
        assertThat(objectMapper.readTree(
                conflict.getResponse().getContentAsByteArray()
        ).path("error").asText()).isEqualTo("PASSWORD_CHANGED_CONCURRENTLY");

        removeDelayTrigger();

        assertThat(loginStatus(user.getUsername(), "Integration-password-2026"))
                .isEqualTo(401);
        int firstNew = loginStatus(user.getUsername(), firstNewPassword);
        int secondNew = loginStatus(user.getUsername(), secondNewPassword);
        assertThat(List.of(firstNew, secondNew))
                .containsExactlyInAnyOrder(200, 401);
    }

    private MvcResult performChange(String accessToken, String newPassword)
            throws Exception {
        return mockMvc.perform(put("/api/user/password")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(
                                "Integration-password-2026",
                                newPassword
                        )))
                .andReturn();
    }

    /**
     * Runs the two change requests on separate threads while the first
     * request is provably still inside its UPDATE. This makes the "both
     * transactions read the same previous hash" precondition deterministic
     * instead of depending on scheduler timing.
     */
    private void awaitDelayedUpdateInProgress() {
        await("password-change UPDATE in progress")
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> activePasswordUpdates() > 0);
    }

    private int activePasswordUpdates() {
        Integer active = jdbcTemplate.queryForObject(
                """
                        SELECT count(*) FROM pg_stat_activity
                        WHERE state = 'active'
                          AND pid <> pg_backend_pid()
                          AND query ILIKE '%UPDATE user_login_methods%'
                          AND query NOT ILIKE '%pg_stat_activity%'
                        """,
                Integer.class
        );
        return active == null ? 0 : active;
    }

    private void installUpdateDelayTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF OLD.local_password_hash IS DISTINCT FROM NEW.local_password_hash THEN
                        PERFORM pg_sleep(%s);
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(DELAY_FUNCTION, DELAY_SECONDS));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE UPDATE ON user_login_methods
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(DELAY_TRIGGER, DELAY_FUNCTION));
    }

    private UserEntity createLocalPasswordUser(String prefix) {
        UserEntity user = createUserShell(prefix);
        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(user.getUsername())
                .localPasswordHash(passwordEncoder.encode(
                        "Integration-password-2026"))
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);
        return userRepository.saveAndFlush(user);
    }

    private UserEntity createUserWithMethod(
            String prefix,
            UserLoginMethod.AuthProvider provider,
            String password) {
        UserEntity user = createUserShell(prefix);
        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(provider)
                .providerUserId(provider == UserLoginMethod.AuthProvider.LOCAL
                        ? null
                        : "provider-" + UUID.randomUUID())
                .localUsername(provider == UserLoginMethod.AuthProvider.LOCAL
                        ? user.getUsername()
                        : null)
                .localPasswordHash(password == null
                        ? null
                        : passwordEncoder.encode(password))
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);
        return userRepository.saveAndFlush(user);
    }

    private UserEntity createUserShell(String prefix) {
        String suffix = UUID.randomUUID().toString();
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(prefix + "-" + suffix);
        user.setEmail(prefix + "-" + suffix + "@example.invalid");
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName("Password Test");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(Set.of("ROLE_USER"));
        return user;
    }

    private String issueAccessToken(String userId, Instant authTime) {
        TokenSessionSnapshot snapshot = tokenSessionTransactionService.create(
                userId,
                authTime,
                null
        );
        return tokenIssuanceFacade.sign(snapshot).accessToken();
    }

    private int loginStatus(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String changePasswordBody(String currentPassword, String newPassword)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "currentPassword", currentPassword,
                "newPassword", newPassword,
                "newPasswordConfirm", newPassword
        ));
    }
}
