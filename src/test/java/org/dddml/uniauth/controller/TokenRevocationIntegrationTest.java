package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.config.IntrospectionProperties;
import org.dddml.uniauth.entity.TokenFamilyEntity;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.TokenFamilyRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.support.AuthIntegrationTestSupport.CsrfContext;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
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
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.authenticatedIntrospection;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.bootstrapCsrf;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.responseCookie;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.withCsrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenRevocationIntegrationTest extends PostgreSqlIntegrationTest {

    private static final String FAILURE_TRIGGER = "test_reject_token_family_revoke";
    private static final String FAILURE_FUNCTION = "test_reject_token_family_revoke";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenFamilyRepository tokenFamilyRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private IntrospectionProperties introspectionProperties;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void removeFailureTrigger() {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS " + FAILURE_TRIGGER + " ON token_families"
        );
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + FAILURE_FUNCTION + "()");
    }

    @Test
    void refreshConsumesThePresentedTokenAndRejectsReplay() throws Exception {
        LoginTokens login = registerAndLogin("refresh-replay");

        MvcResult firstRefresh = refresh(login);
        assertThat(firstRefresh.getResponse().getStatus()).isEqualTo(200);
        JsonNode firstBody = responseJson(firstRefresh);
        assertThat(firstBody.has("refreshToken")).isFalse();
        String rotatedRefreshToken = responseCookie(
                firstRefresh,
                "refreshToken"
        );
        assertThat(rotatedRefreshToken)
                .isNotEqualTo(login.refreshToken());

        TokenFamilyEntity rotated = tokenFamily(login.familyId());
        assertThat(rotated.getCurrentGeneration()).isEqualTo(1);
        assertThat(rotated.getRevokedAt()).isNull();

        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        login.csrf()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Token refresh failed"));

        TokenFamilyEntity replayRevoked = tokenFamily(login.familyId());
        assertThat(replayRevoked.getCurrentGeneration()).isEqualTo(1);
        assertThat(replayRevoked.getRevokedAt()).isNotNull();
        assertThat(replayRevoked.getRevokeReason()).isEqualTo("REFRESH_REPLAY");

        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        rotatedRefreshToken
                                )),
                        login.csrf()
                ))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/user")
                        .header(
                                "Authorization",
                                "Bearer " + firstBody.path("accessToken").asText()
                        ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshAllowsOneRotationAndRejectsOneReplay() throws Exception {
        LoginTokens login = registerAndLogin("refresh-concurrent");

        List<MvcResult> results = runConcurrently(
                () -> refresh(login),
                () -> refresh(login)
        );

        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 401);
        TokenFamilyEntity family = tokenFamily(login.familyId());
        assertThat(family.getCurrentGeneration()).isEqualTo(1);
        assertThat(family.getRevokedAt()).isNotNull();
        assertThat(family.getRevokeReason()).isEqualTo("REFRESH_REPLAY");

        MvcResult successful = results.stream()
                .filter(result -> result.getResponse().getStatus() == 200)
                .findFirst()
                .orElseThrow();
        mockMvc.perform(get("/api/user")
                        .header(
                                "Authorization",
                                "Bearer "
                                        + responseJson(successful)
                                                .path("accessToken")
                                                .asText()
                        ))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/logout", "/api/logout"})
    void logoutRevokesCurrentTokensAcrossBothSupportedRoutes(String logoutPath)
            throws Exception {
        LoginTokens login = registerAndLogin("logout-route");

        mockMvc.perform(withCsrf(
                        post(logoutPath)
                                .cookie(
                                        new Cookie(
                                                "accessToken",
                                                login.accessToken()
                                        ),
                                        new Cookie(
                                                "refreshToken",
                                                login.refreshToken()
                                        )
                                ),
                        login.csrf()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0));

        assertRevokedFamily(login.familyId(), login.userId(), "LOGOUT");

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + login.accessToken()))
                .andExpect(status().isUnauthorized());
        CsrfContext postLogoutCsrf = bootstrapCsrf(mockMvc, objectMapper);
        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        postLogoutCsrf
                ))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authenticatedIntrospection(
                        post("/oauth2/introspect")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("token", login.accessToken()),
                        introspectionProperties
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void logoutRevokesAccessTokenFromCaseInsensitiveBearerScheme() throws Exception {
        LoginTokens login = registerAndLogin("logout-lowercase-bearer");

        mockMvc.perform(withCsrf(
                        post("/api/auth/logout")
                                .header(
                                        "Authorization",
                                        "bearer " + login.accessToken()
                                )
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        login.csrf()
                ))
                .andExpect(status().isOk());

        assertRevokedFamily(login.familyId(), login.userId(), "LOGOUT");
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
        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        login.csrf()
                ))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authenticatedIntrospection(
                        post("/oauth2/introspect")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("token", login.accessToken()),
                        introspectionProperties
                ))
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
        CsrfContext csrf = bootstrapCsrf(mockMvc, objectMapper);

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        refreshToken
                                )),
                        csrf
                ))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(authenticatedIntrospection(
                        post("/oauth2/introspect")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .param("token", accessToken),
                        introspectionProperties
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void refreshTokenWithoutJtiIsRejected() throws Exception {
        LoginTokens login = registerAndLogin("refresh-missing-jti");
        String username = userRepository.findById(login.userId()).orElseThrow().getUsername();
        Claims validClaims = claims(login.refreshToken());
        var now = java.time.Instant.now();
        String refreshToken = Jwts.builder()
                .setClaims(Map.of(
                        "userId", login.userId(),
                        "username", username,
                        "type", "refresh",
                        "iss", jwtTokenService.getToken().getIssuer(),
                        "sid", validClaims.get("sid", String.class),
                        "generation", validClaims.get("generation", Number.class),
                        "ver", validClaims.get("ver", Number.class),
                        "auth_time", validClaims.get("auth_time", Number.class)
                ))
                .setSubject(login.userId())
                .setIssuedAt(Date.from(now.minusSeconds(5)))
                .setExpiration(Date.from(now.plusSeconds(300)))
                .setHeaderParam("kid", jwtTokenService.getToken().getKid())
                .signWith(jwtTokenService.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        refreshToken
                                )),
                        login.csrf()
                ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Token refresh failed"));
    }

    @Test
    void logoutReportsIncompleteRevocationButStillClearsLocalStateOnDatabaseFailure()
            throws Exception {
        LoginTokens login = registerAndLogin("logout-database-failure");
        installFailureTrigger();

        mockMvc.perform(withCsrf(
                        post("/api/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer " + login.accessToken()
                                )
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        login.csrf()
                ))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("REVOCATION_INCOMPLETE"))
                .andExpect(cookie().maxAge("accessToken", 0))
                .andExpect(cookie().maxAge("refreshToken", 0));

        TokenFamilyEntity family = tokenFamily(login.familyId());
        assertThat(family.getRevokedAt()).isNull();
        assertThat(family.getRevokeReason()).isNull();
    }

    @Test
    void cookieAuthenticatedRefreshRequiresOneExactCsrfHeader()
            throws Exception {
        LoginTokens login = registerAndLogin("refresh-csrf");

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(
                                "refreshToken",
                                login.refreshToken()
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(
                                login.csrf().sessionCookie(),
                                new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )
                        )
                        .header(login.csrf().headerName(), "wrong-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(
                                login.csrf().sessionCookie(),
                                new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )
                        )
                        .header(
                                login.csrf().headerName(),
                                login.csrf().token(),
                                login.csrf().token()
                        ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_TOKEN_INVALID"));

        mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        login.csrf()
                ))
                .andExpect(status().isOk());
    }

    @Test
    void logoutUsesValidRefreshWhenAccessTokenIsExpired() throws Exception {
        LoginTokens login = registerAndLogin("logout-expired-access");
        String expiredAccessToken = expiredAccessToken(login);

        mockMvc.perform(withCsrf(
                        post("/api/auth/logout")
                                .cookie(
                                        new Cookie(
                                                "accessToken",
                                                expiredAccessToken
                                        ),
                                        new Cookie(
                                                "refreshToken",
                                                login.refreshToken()
                                        )
                                ),
                        login.csrf()
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Logged out successfully"));

        assertRevokedFamily(login.familyId(), login.userId(), "LOGOUT");
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
        String accessToken = body.path("accessToken").asText();
        String refreshToken = responseCookie(result, "refreshToken");
        Claims accessClaims = claims(accessToken);
        return new LoginTokens(
                user.getId(),
                accessToken,
                refreshToken,
                accessClaims.get("sid", String.class),
                bootstrapCsrf(mockMvc, objectMapper)
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

    private MvcResult refresh(LoginTokens login) throws Exception {
        return mockMvc.perform(withCsrf(
                        post("/api/auth/refresh")
                                .cookie(new Cookie(
                                        "refreshToken",
                                        login.refreshToken()
                                )),
                        login.csrf()
                ))
                .andReturn();
    }

    private Claims claims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(jwtTokenService.getPublicKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private String expiredAccessToken(LoginTokens login) {
        Claims current = claims(login.accessToken());
        Map<String, Object> expiredClaims = new HashMap<>(current);
        expiredClaims.remove("iat");
        expiredClaims.remove("exp");
        Instant now = Instant.now();
        return Jwts.builder()
                .setClaims(expiredClaims)
                .setIssuedAt(Date.from(now.minusSeconds(120)))
                .setExpiration(Date.from(now.minusSeconds(60)))
                .setHeaderParam("kid", jwtTokenService.getToken().getKid())
                .signWith(
                        jwtTokenService.getPrivateKey(),
                        SignatureAlgorithm.RS256
                )
                .compact();
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertRevokedFamily(
            String familyId,
            String userId,
            String reason) {
        TokenFamilyEntity family = tokenFamily(familyId);
        assertThat(family.getUserId()).isEqualTo(userId);
        assertThat(family.getRevokeReason()).isEqualTo(reason);
        assertThat(family.getRevokedAt()).isNotNull();
    }

    private TokenFamilyEntity tokenFamily(String familyId) {
        entityManager.clear();
        return tokenFamilyRepository.findById(familyId).orElseThrow();
    }

    private void installFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL THEN
                        RAISE EXCEPTION 'injected token family failure';
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(FAILURE_FUNCTION));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE UPDATE ON token_families
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

    private record LoginTokens(
            String userId,
            String accessToken,
            String refreshToken,
            String familyId,
            CsrfContext csrf) {
    }
}
