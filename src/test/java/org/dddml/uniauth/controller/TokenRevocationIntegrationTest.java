package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.entity.TokenBlacklistEntity;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.TokenBlacklistRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenRevocationIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String FAILURE_TRIGGER = "test_reject_token_blacklist_insert";
    private static final String FAILURE_FUNCTION = "test_reject_token_blacklist_insert";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenBlacklistRepository tokenBlacklistRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER + " ON token_blacklist"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FAILURE_FUNCTION + "()");
    }

    @Test
    void refreshConsumesThePresentedTokenAndRejectsReplay() throws Exception {
        LoginTokens login = registerAndLogin("refresh-replay");
        String oldRefreshJti = claims(login.refreshToken()).getId();

        MvcResult firstRefresh = refresh(login.refreshToken());
        assertThat(firstRefresh.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = responseJson(firstRefresh);
        assertThat(firstBody.path("refreshToken").asText())
                .isNotBlank()
                .isNotEqualTo(login.refreshToken());

        TokenBlacklistEntity consumed = tokenBlacklistRepository
                .findByJti(oldRefreshJti)
                .orElseThrow();
        assertThat(consumed.getTokenType())
                .isEqualTo(TokenBlacklistEntity.TokenType.REFRESH);
        assertThat(consumed.getUserId()).isEqualTo(login.userId());
        assertThat(consumed.getReason()).isEqualTo("REFRESH_ROTATED");

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", login.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Token refresh failed"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(
                                "refreshToken",
                                firstBody.path("refreshToken").asText()
                        )))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentRefreshAllowsOneRotationAndRejectsOneReplay() throws Exception {
        LoginTokens login = registerAndLogin("refresh-concurrent");
        String oldRefreshJti = claims(login.refreshToken()).getId();

        List<MvcResult> results = runConcurrently(
                () -> refresh(login.refreshToken()),
                () -> refresh(login.refreshToken())
        );

        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 401);
        assertThat(tokenBlacklistRepository.findAll())
                .filteredOn(entry -> oldRefreshJti.equals(entry.getJti()))
                .singleElement()
                .extracting(TokenBlacklistEntity::getTokenType)
                .isEqualTo(TokenBlacklistEntity.TokenType.REFRESH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/logout", "/api/logout"})
    void logoutRevokesCurrentTokensAcrossBothSupportedRoutes(String logoutPath)
            throws Exception {
        LoginTokens login = registerAndLogin("logout-route");
        String accessJti = claims(login.accessToken()).getId();
        String refreshJti = claims(login.refreshToken()).getId();

        mockMvc.perform(post(logoutPath)
                        .cookie(
                                new Cookie("accessToken", login.accessToken()),
                                new Cookie("refreshToken", login.refreshToken())
                        ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0));

        assertBlacklistEntry(
                accessJti,
                login.userId(),
                TokenBlacklistEntity.TokenType.ACCESS,
                "LOGOUT"
        );
        assertBlacklistEntry(
                refreshJti,
                login.userId(),
                TokenBlacklistEntity.TokenType.REFRESH,
                "LOGOUT"
        );

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", login.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void logoutRevokesAccessTokenFromCaseInsensitiveBearerScheme() throws Exception {
        LoginTokens login = registerAndLogin("logout-lowercase-bearer");
        String accessJti = claims(login.accessToken()).getId();
        String refreshJti = claims(login.refreshToken()).getId();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "bearer " + login.accessToken())
                        .cookie(new Cookie("refreshToken", login.refreshToken())))
                .andExpect(status().isOk());

        assertBlacklistEntry(
                accessJti,
                login.userId(),
                TokenBlacklistEntity.TokenType.ACCESS,
                "LOGOUT"
        );
        assertBlacklistEntry(
                refreshJti,
                login.userId(),
                TokenBlacklistEntity.TokenType.REFRESH,
                "LOGOUT"
        );
    }

    @Test
    void disabledUserCannotUseExistingAccessOrRefreshTokens() throws Exception {
        LoginTokens login = registerAndLogin("disabled-token");
        UserEntity user = userRepository.findById(login.userId()).orElseThrow();
        user.setEnabled(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", login.refreshToken())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", login.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void missingUserCannotUseExistingAccessOrRefreshTokens() throws Exception {
        String userId = UUID.randomUUID().toString();
        String username = "missing-token-user";
        String accessToken = jwtTokenService.generateAccessToken(
                username,
                username + "@example.invalid",
                userId,
                Set.of("ROLE_USER")
        );
        String refreshToken = jwtTokenService.generateRefreshToken(username, userId);

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/introspect")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void refreshTokenWithoutJtiIsRejected() throws Exception {
        LoginTokens login = registerAndLogin("refresh-missing-jti");
        String username = userRepository.findById(login.userId()).orElseThrow().getUsername();
        var now = java.time.Instant.now();
        String refreshToken = Jwts.builder()
                .setClaims(Map.of(
                        "userId", login.userId(),
                        "username", username,
                        "type", "refresh",
                        "iss", jwtTokenService.getToken().getIssuer()
                ))
                .setSubject(login.userId())
                .setIssuedAt(Date.from(now.minusSeconds(5)))
                .setExpiration(Date.from(now.plusSeconds(300)))
                .setHeaderParam("kid", jwtTokenService.getToken().getKid())
                .signWith(jwtTokenService.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Token refresh failed"));
    }

    @Test
    void logoutReportsIncompleteRevocationButStillClearsLocalStateOnDatabaseFailure()
            throws Exception {
        LoginTokens login = registerAndLogin("logout-database-failure");
        installFailureTrigger();

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + login.accessToken())
                        .cookie(new Cookie("refreshToken", login.refreshToken())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("REVOCATION_INCOMPLETE"))
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0));

        assertThat(tokenBlacklistRepository.findByJti(claims(login.accessToken()).getId()))
                .isEmpty();
        assertThat(tokenBlacklistRepository.findByJti(claims(login.refreshToken()).getId()))
                .isEmpty();
    }

    private LoginTokens registerAndLogin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String username = prefix + "-" + suffix;
        String password = "integration-password";
        UserEntity user = createLocalUser(
                username,
                username + "@example.invalid",
                password
        );

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = responseJson(result);
        return new LoginTokens(
                user.getId(),
                body.path("accessToken").asText(),
                body.path("refreshToken").asText()
        );
    }

    private UserEntity createLocalUser(
            String username,
            String email,
            String password) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName("Token Revocation User");
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
        return userRepository.saveAndFlush(user);
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refreshToken", refreshToken)))
                .andReturn();
    }

    private Claims claims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(jwtTokenService.getPublicKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertBlacklistEntry(
            String jti,
            String userId,
            TokenBlacklistEntity.TokenType type,
            String reason) {
        TokenBlacklistEntity entry = tokenBlacklistRepository.findByJti(jti).orElseThrow();
        assertThat(entry.getUserId()).isEqualTo(userId);
        assertThat(entry.getTokenType()).isEqualTo(type);
        assertThat(entry.getReason()).isEqualTo(reason);
        assertThat(entry.getExpiresAt()).isNotNull();
        assertThat(entry.getBlacklistedAt()).isNotNull();
    }

    private void installFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF NEW.token_type = 'REFRESH' THEN
                        RAISE EXCEPTION 'injected token blacklist failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(FAILURE_FUNCTION));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE INSERT ON token_blacklist
                FOR EACH ROW EXECUTE FUNCTION %s()
                """.formatted(FAILURE_TRIGGER, FAILURE_FUNCTION));
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

    private record LoginTokens(String userId, String accessToken, String refreshToken) {
    }
}
