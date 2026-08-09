package org.dddml.uniauth.controller;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.dto.RegisterRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenSessionTransactionService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.support.AuthIntegrationTestSupport.IssuedTokens;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.dddml.uniauth.support.AuthIntegrationTestSupport.issueTokens;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtBoundaryIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private UserService userService;

    @Autowired
    private TokenSessionTransactionService tokenSessionTransactionService;

    @Autowired
    private TokenIssuanceFacade tokenIssuanceFacade;

    @ParameterizedTest
    @ValueSource(strings = {
            "wrong-issuer",
            "wrong-audience",
            "wrong-type",
            "expired"
    })
    void protectedApiRejectsInvalidAccessTokenClaims(String scenario) throws Exception {
        UserDto user = registerUser("jwt-" + scenario);
        String token = switch (scenario) {
            case "wrong-issuer" -> signedToken(
                    user,
                    "https://wrong-issuer.example",
                    jwtTokenService.getToken().getAudience(),
                    "access",
                    Instant.now().minusSeconds(5),
                    Instant.now().plusSeconds(300)
            );
            case "wrong-audience" -> signedToken(
                    user,
                    jwtTokenService.getToken().getIssuer(),
                    "wrong-audience",
                    "access",
                    Instant.now().minusSeconds(5),
                    Instant.now().plusSeconds(300)
            );
            case "wrong-type" -> signedToken(
                    user,
                    jwtTokenService.getToken().getIssuer(),
                    jwtTokenService.getToken().getAudience(),
                    "refresh",
                    Instant.now().minusSeconds(5),
                    Instant.now().plusSeconds(300)
            );
            case "expired" -> signedToken(
                    user,
                    jwtTokenService.getToken().getIssuer(),
                    jwtTokenService.getToken().getAudience(),
                    "access",
                    Instant.now().minusSeconds(600),
                    Instant.now().minusSeconds(60)
            );
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApiRejectsTamperedSignature() throws Exception {
        UserDto user = registerUser("jwt-tampered");
        String validToken = jwtTokenService.generateAccessToken(
                user.getUsername(),
                user.getEmail(),
                user.getId(),
                user.getAuthorities()
        );
        String[] segments = validToken.split("\\.");
        int tamperIndex = segments[2].length() / 2;
        char original = segments[2].charAt(tamperIndex);
        char replacement = original == 'A' ? 'B' : 'A';
        segments[2] = segments[2].substring(0, tamperIndex)
                + replacement
                + segments[2].substring(tamperIndex + 1);
        String tamperedToken = String.join(".", segments);

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApiRejectsAccessTokenWithoutJti() throws Exception {
        UserDto user = registerUser("jwt-missing-jti");
        Instant now = Instant.now();
        Claims validClaims = claims(accessToken(user));
        String token = Jwts.builder()
                .setClaims(Map.of(
                        "userId", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "authorities", Set.of("ROLE_USER"),
                        "type", "access",
                        "sid", validClaims.get("sid", String.class),
                        "generation", validClaims.get("generation", Number.class),
                        "ver", validClaims.get("ver", Number.class),
                        "auth_time", validClaims.get("auth_time", Number.class)
                ))
                .setSubject(user.getId())
                .setIssuer(jwtTokenService.getToken().getIssuer())
                .setAudience(jwtTokenService.getToken().getAudience())
                .setIssuedAt(Date.from(now.minusSeconds(5)))
                .setExpiration(Date.from(now.plusSeconds(300)))
                .setHeaderParam("kid", jwtTokenService.getToken().getKid())
                .signWith(jwtTokenService.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApiAcceptsCaseInsensitiveBearerScheme() throws Exception {
        UserDto user = registerUser("jwt-lowercase-bearer");

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "bearer " + accessToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()));
    }

    @Test
    void conflictingAuthorizationHeaderAndAccessTokenCookieAreRejected()
            throws Exception {
        UserDto headerUser = registerUser("jwt-header");
        UserDto cookieUser = registerUser("jwt-cookie");
        String headerToken = accessToken(headerUser);
        String cookieToken = accessToken(cookieUser);

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + headerToken)
                        .cookie(new Cookie("accessToken", cookieToken)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/user")
                        .cookie(new Cookie("accessToken", cookieToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(cookieUser.getId()))
                .andExpect(jsonPath("$.userName").value(cookieUser.getUsername()));
    }

    @Test
    void matchingAuthorizationHeaderAndAccessTokenCookieAreAccepted()
            throws Exception {
        UserDto user = registerUser("jwt-matching-credential");
        String accessToken = accessToken(user);

        mockMvc.perform(get("/api/user")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(new Cookie("accessToken", accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId()));
    }

    @Test
    void duplicateAuthorizationHeadersAreRejected() throws Exception {
        UserDto user = registerUser("jwt-duplicate-header");
        String accessToken = accessToken(user);

        mockMvc.perform(get("/api/user")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + accessToken,
                                "Bearer " + accessToken
                        ))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateAccessTokenCookiesAreRejected() throws Exception {
        UserDto user = registerUser("jwt-duplicate-cookie");
        String accessToken = accessToken(user);

        mockMvc.perform(get("/api/user")
                        .header(
                                HttpHeaders.COOKIE,
                                "accessToken=" + accessToken
                                        + "; accessToken=" + accessToken
                        ))
                .andExpect(status().isUnauthorized());
    }

    private UserDto registerUser(String prefix) {
        String suffix = UUID.randomUUID().toString();
        String username = prefix + "-" + suffix;
        return userService.register(new RegisterRequest(
                username,
                username + "@example.invalid",
                "integration-password",
                "JWT Boundary User",
                null,
                null
        ));
    }

    private String accessToken(UserDto user) {
        return issueTokens(
                tokenSessionTransactionService,
                tokenIssuanceFacade,
                user.getId()
        ).accessToken();
    }

    private String signedToken(
            UserDto user,
            String issuer,
            String audience,
            String type,
            Instant issuedAt,
            Instant expiresAt) {
        IssuedTokens issuedTokens = issueTokens(
                tokenSessionTransactionService,
                tokenIssuanceFacade,
                user.getId()
        );
        Claims sessionClaims = claims(issuedTokens.accessToken());
        return Jwts.builder()
                .setClaims(Map.of(
                        "userId", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "authorities", Set.of("ROLE_USER"),
                        "type", type,
                        "jti", UUID.randomUUID().toString(),
                        "sid", sessionClaims.get("sid", String.class),
                        "generation", sessionClaims.get("generation", Number.class),
                        "ver", sessionClaims.get("ver", Number.class),
                        "auth_time", sessionClaims.get("auth_time", Number.class)
                ))
                .setSubject(user.getId())
                .setIssuer(issuer)
                .setAudience(audience)
                .setIssuedAt(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .setHeaderParam("kid", jwtTokenService.getToken().getKid())
                .signWith(jwtTokenService.getPrivateKey(), SignatureAlgorithm.RS256)
                .compact();
    }

    private Claims claims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(jwtTokenService.getPublicKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
