package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.uniauth.dto.RegisterRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginMethodConcurrencyIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String DELAY_TRIGGER = "test_delay_login_method_race";
    private static final String DELAY_FUNCTION = "test_delay_login_method_race";

    @Autowired
    private LoginMethodService loginMethodService;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentOAuthBindingReturnsOneStableConflictAndPersistsOneOwner()
            throws Exception {
        UserDto firstUser = registerUser("bind-first");
        UserDto secondUser = registerUser("bind-second");
        String providerSubject = "concurrent-bind-" + UUID.randomUUID();

        installInsertDelayTrigger();
        try {
            List<BindResult> results = runConcurrently(
                    () -> bind(firstUser.getId(), providerSubject),
                    () -> bind(secondUser.getId(), providerSubject)
            );

            assertThat(results).filteredOn(BindResult::success).hasSize(1);
            assertThat(results).filteredOn(result -> !result.success())
                    .singleElement()
                    .extracting(BindResult::message)
                    .isEqualTo("该OAuth2账户已被绑定");
            assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                    UserLoginMethod.AuthProvider.GITHUB,
                    providerSubject
            )).isPresent();
        } finally {
            removeDelayTrigger();
        }
    }

    @Test
    void concurrentOAuthBindingForSameUserAndSubjectReturnsTruthfulConflict()
            throws Exception {
        UserDto user = registerUser("bind-same-subject");
        String providerSubject = "concurrent-bind-" + UUID.randomUUID();

        installInsertDelayTrigger();
        try {
            List<BindResult> results = runConcurrently(
                    () -> bind(user.getId(), providerSubject),
                    () -> bind(user.getId(), providerSubject)
            );

            assertThat(results).filteredOn(BindResult::success).hasSize(1);
            assertThat(results).filteredOn(result -> !result.success())
                    .singleElement()
                    .extracting(BindResult::message)
                    .isEqualTo("该OAuth2账户已被绑定");
            assertThat(loginMethodRepository.findByUserId(user.getId()))
                    .filteredOn(method ->
                            method.getAuthProvider()
                                    == UserLoginMethod.AuthProvider.GITHUB)
                    .singleElement()
                    .extracting(UserLoginMethod::getProviderUserId)
                    .isEqualTo(providerSubject);
        } finally {
            removeDelayTrigger();
        }
    }

    @Test
    void concurrentOAuthBindingForSameUserAndProviderPersistsOneMethod()
            throws Exception {
        UserDto user = registerUser("bind-same-user");
        String firstSubject = "concurrent-bind-" + UUID.randomUUID();
        String secondSubject = "concurrent-bind-" + UUID.randomUUID();

        installInsertDelayTrigger();
        try {
            List<BindResult> results = runConcurrently(
                    () -> bind(user.getId(), firstSubject),
                    () -> bind(user.getId(), secondSubject)
            );

            assertThat(results).filteredOn(BindResult::success).hasSize(1);
            assertThat(results).filteredOn(result -> !result.success())
                    .singleElement()
                    .extracting(BindResult::message)
                    .isEqualTo("用户已绑定该登录方式");

            assertThat(loginMethodRepository.findByUserId(user.getId()))
                    .filteredOn(method ->
                            method.getAuthProvider()
                                    == UserLoginMethod.AuthProvider.GITHUB)
                    .singleElement()
                    .extracting(UserLoginMethod::getProviderUserId)
                    .isIn(firstSubject, secondSubject);
        } finally {
            removeDelayTrigger();
        }
    }

    @Test
    void concurrentSetPrimaryReturnsStableConflictAndLeavesExactlyOnePrimary()
            throws Exception {
        UserDto user = registerUser("primary-race");
        UserLoginMethod github = loginMethodService.bindOAuth2LoginMethod(
                user.getId(),
                UserLoginMethod.AuthProvider.GITHUB,
                "primary-github-" + UUID.randomUUID(),
                user.getEmail(),
                "Primary GitHub"
        );
        UserLoginMethod google = loginMethodService.bindOAuth2LoginMethod(
                user.getId(),
                UserLoginMethod.AuthProvider.GOOGLE,
                "primary-google-" + UUID.randomUUID(),
                user.getEmail(),
                "Primary Google"
        );
        String accessToken = jwtTokenService.generateAccessToken(
                user.getUsername(),
                user.getEmail(),
                user.getId(),
                Set.of("ROLE_USER")
        );

        installPrimaryClearDelayTrigger();
        try {
            List<MvcResult> results = runConcurrently(
                    () -> setPrimary(accessToken, github.getId()),
                    () -> setPrimary(accessToken, google.getId())
            );

            assertThat(results)
                    .extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(200, 409);

            MvcResult conflict = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .findFirst()
                    .orElseThrow();
            JsonNode conflictBody =
                    objectMapper.readTree(conflict.getResponse().getContentAsByteArray());
            assertThat(conflictBody.path("error").asText())
                    .isEqualTo("主登录方式已被并发修改，请重试");

            List<UserLoginMethod> methods = loginMethodRepository.findByUserId(user.getId());
            assertThat(methods).filteredOn(UserLoginMethod::isPrimary).hasSize(1);
            assertThat(methods.stream()
                    .filter(UserLoginMethod::isPrimary)
                    .map(UserLoginMethod::getId)
                    .findFirst()
                    .orElseThrow()).isIn(github.getId(), google.getId());
        } finally {
            removeDelayTrigger();
        }
    }

    @Test
    void concurrentRemovalOfBothMethodsReturnsStableConflictAndKeepsOnePrimary()
            throws Exception {
        UserDto user = registerUser("remove-race");
        UserLoginMethod local = loginMethodRepository
                .findByUserIdAndAuthProvider(
                        user.getId(),
                        UserLoginMethod.AuthProvider.LOCAL
                )
                .orElseThrow();
        UserLoginMethod github = loginMethodService.bindOAuth2LoginMethod(
                user.getId(),
                UserLoginMethod.AuthProvider.GITHUB,
                "remove-github-" + UUID.randomUUID(),
                user.getEmail(),
                "Remove GitHub"
        );
        String accessToken = accessToken(user);

        installLoginMethodMutationDelayTrigger();
        try {
            List<MvcResult> results = runConcurrently(
                    () -> remove(accessToken, local.getId()),
                    () -> remove(accessToken, github.getId())
            );

            assertOneSuccessAndOneConflict(
                    results,
                    "登录方式已被并发修改，请重试"
            );
            assertLoginMethodInvariant(user.getId(), 1);
        } finally {
            removeDelayTrigger();
        }
    }

    @Test
    void concurrentPrimaryRemovalAndPrimarySwitchKeepsLoginMethodInvariant()
            throws Exception {
        UserDto user = registerUser("remove-primary-race");
        UserLoginMethod local = loginMethodRepository
                .findByUserIdAndAuthProvider(
                        user.getId(),
                        UserLoginMethod.AuthProvider.LOCAL
                )
                .orElseThrow();
        UserLoginMethod github = loginMethodService.bindOAuth2LoginMethod(
                user.getId(),
                UserLoginMethod.AuthProvider.GITHUB,
                "remove-primary-github-" + UUID.randomUUID(),
                user.getEmail(),
                "Remove Primary GitHub"
        );
        String accessToken = accessToken(user);

        installLoginMethodMutationDelayTrigger();
        try {
            List<MvcResult> results = runConcurrently(
                    () -> remove(accessToken, local.getId()),
                    () -> setPrimary(accessToken, github.getId())
            );

            assertOneSuccessAndOneConflict(
                    results,
                    Set.of(
                            "登录方式已被并发修改，请重试",
                            "主登录方式已被并发修改，请重试"
                    )
            );
            assertLoginMethodInvariant(user.getId(), null);
        } finally {
            removeDelayTrigger();
        }
    }

    @Test
    void concurrentTargetRemovalAndPrimarySwitchKeepsLoginMethodInvariant()
            throws Exception {
        UserDto user = registerUser("remove-target-race");
        UserLoginMethod github = loginMethodService.bindOAuth2LoginMethod(
                user.getId(),
                UserLoginMethod.AuthProvider.GITHUB,
                "remove-target-github-" + UUID.randomUUID(),
                user.getEmail(),
                "Remove Target GitHub"
        );
        String accessToken = accessToken(user);

        installLoginMethodMutationDelayTrigger();
        try {
            List<MvcResult> results = runConcurrently(
                    () -> remove(accessToken, github.getId()),
                    () -> setPrimary(accessToken, github.getId())
            );

            assertOneSuccessAndOneConflict(
                    results,
                    Set.of(
                            "登录方式已被并发修改，请重试",
                            "主登录方式已被并发修改，请重试"
                    )
            );
            assertLoginMethodInvariant(user.getId(), null);
        } finally {
            removeDelayTrigger();
        }
    }

    private BindResult bind(String userId, String providerSubject) {
        try {
            loginMethodService.bindOAuth2LoginMethod(
                    userId,
                    UserLoginMethod.AuthProvider.GITHUB,
                    providerSubject,
                    providerSubject + "@example.invalid",
                    "Concurrent Binding"
            );
            return new BindResult(true, null);
        } catch (RuntimeException exception) {
            return new BindResult(false, exception.getMessage());
        }
    }

    private MvcResult setPrimary(String accessToken, String methodId) throws Exception {
        return mockMvc.perform(put("/api/user/login-methods/{id}/primary", methodId)
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn();
    }

    private MvcResult remove(String accessToken, String methodId) throws Exception {
        return mockMvc.perform(delete("/api/user/login-methods/{id}", methodId)
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn();
    }

    private String accessToken(UserDto user) {
        return jwtTokenService.generateAccessToken(
                user.getUsername(),
                user.getEmail(),
                user.getId(),
                Set.of("ROLE_USER")
        );
    }

    private void assertOneSuccessAndOneConflict(
            List<MvcResult> results,
            String expectedMessage) throws Exception {
        assertOneSuccessAndOneConflict(results, Set.of(expectedMessage));
    }

    private void assertOneSuccessAndOneConflict(
            List<MvcResult> results,
            Set<String> expectedMessages) throws Exception {
        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 409);

        MvcResult conflict = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst()
                .orElseThrow();
        JsonNode conflictBody =
                objectMapper.readTree(conflict.getResponse().getContentAsByteArray());
        assertThat(conflictBody.path("error").asText()).isIn(expectedMessages);
    }

    private void assertLoginMethodInvariant(String userId, Integer expectedCount) {
        List<UserLoginMethod> methods = loginMethodRepository.findByUserId(userId);
        assertThat(methods).isNotEmpty();
        if (expectedCount != null) {
            assertThat(methods).hasSize(expectedCount);
        }
        assertThat(methods).filteredOn(UserLoginMethod::isPrimary).hasSize(1);
    }

    private UserDto registerUser(String prefix) {
        String suffix = UUID.randomUUID().toString();
        return userService.register(new RegisterRequest(
                prefix + "-" + suffix,
                prefix + "-" + suffix + "@example.invalid",
                "integration-password",
                "Concurrency User",
                null,
                null
        ));
    }

    private void installInsertDelayTrigger() {
        installDelayFunction("""
                IF NEW.auth_provider = 'GITHUB'
                   AND NEW.provider_user_id LIKE 'concurrent-bind-%%' THEN
                    PERFORM pg_sleep(0.5);
                END IF;
                """, "INSERT");
    }

    private void installPrimaryClearDelayTrigger() {
        installDelayFunction("""
                IF OLD.is_primary IS TRUE AND NEW.is_primary IS FALSE THEN
                    PERFORM pg_sleep(0.5);
                END IF;
                """, "UPDATE");
    }

    private void installLoginMethodMutationDelayTrigger() {
        installDelayFunction("PERFORM pg_sleep(0.75);", "UPDATE OR DELETE");
    }

    private void installDelayFunction(String body, String event) {
        removeDelayTrigger();
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    %s
                    IF TG_OP = 'DELETE' THEN
                        RETURN OLD;
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(DELAY_FUNCTION, body));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE %s ON user_login_methods
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(DELAY_TRIGGER, event, DELAY_FUNCTION));
    }

    private void removeDelayTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS " + DELAY_TRIGGER + " ON user_login_methods"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + DELAY_FUNCTION + "()");
    }

    @SafeVarargs
    private <T> List<T> runConcurrently(Callable<T>... tasks) throws Exception {
        var executor = Executors.newFixedThreadPool(tasks.length);
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent test start timed out");
                    }
                    return task.call();
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record BindResult(boolean success, String message) {
    }
}
