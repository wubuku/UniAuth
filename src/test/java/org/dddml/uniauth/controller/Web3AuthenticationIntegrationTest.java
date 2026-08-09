package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.repository.Web3NonceRepository;
import org.dddml.uniauth.service.Web3NonceService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dddml.uniauth.support.AuthIntegrationTestSupport.responseCookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Web3AuthenticationIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private Web3NonceRepository web3NonceRepository;

    @Autowired
    private Web3NonceService web3NonceService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void signedNonceCreatesOneWeb3UserAndCannotBeReplayed() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());

        SignedChallenge challenge = requestSignedChallenge(walletAddress, keyPair);
        MvcResult firstLogin = mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value(walletAddress))
                .andExpect(jsonPath("$.isNewUser").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andReturn();

        String userId = responseJson(firstLogin).path("userId").asText();
        assertTokenCookies(firstLogin);
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.WEB3,
                walletAddress
        )).get().satisfies(method -> {
            assertThat(method.getUser().getId()).isEqualTo(userId);
            assertThat(method.isPrimary()).isTrue();
        });

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        mockMvc.perform(get("/api/auth/web3/status/{wallet}", walletAddress))
                .andExpect(status().isNotFound());

        SignedChallenge secondChallenge = requestSignedChallenge(walletAddress, keyPair);
        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondChallenge.requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.isNewUser").value(false));

        mockMvc.perform(get("/api/auth/web3/nonce/not-a-wallet"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ADDRESS"));
    }

    @Test
    void authenticatedLocalUserCanBindOnlyOneWallet() throws Exception {
        String username = "web3-bind-local";
        String password = "web3-bind-password";
        String userId = createLocalUser(
                username,
                "web3-bind-local@example.invalid",
                password
        ).getId();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode loginBody = responseJson(loginResult);
        String accessToken = loginBody.path("accessToken").asText();
        String refreshToken = responseCookie(loginResult, "refreshToken");

        ECKeyPair firstKeyPair = Keys.createEcKeyPair();
        String firstWallet = "0x" + Keys.getAddress(firstKeyPair.getPublicKey());
        SignedChallenge firstChallenge = requestSignedChallenge(firstWallet, firstKeyPair);
        mockMvc.perform(post("/api/auth/web3/bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstChallenge.requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("MISSING_TOKEN"));

        mockMvc.perform(post("/api/auth/web3/bind")
                        .header("Authorization", "Bearer " + refreshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstChallenge.requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_TOKEN"));

        mockMvc.perform(post("/api/auth/web3/bind")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstChallenge.requestBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("SUCCESS"));

        assertThat(loginMethodRepository.findByUserIdAndAuthProvider(
                userId,
                UserLoginMethod.AuthProvider.WEB3
        )).get().extracting(UserLoginMethod::getProviderUserId).isEqualTo(firstWallet);

        MvcResult renewedLogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String renewedAccessToken = responseJson(renewedLogin)
                .path("accessToken")
                .asText();

        ECKeyPair secondKeyPair = Keys.createEcKeyPair();
        String secondWallet = "0x" + Keys.getAddress(secondKeyPair.getPublicKey());
        SignedChallenge secondChallenge = requestSignedChallenge(secondWallet, secondKeyPair);
        mockMvc.perform(post("/api/auth/web3/bind")
                        .header("Authorization", "Bearer " + renewedAccessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(secondChallenge.requestBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BINDING_FAILED"));
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
        user.setDisplayName("Web3 Bind");
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

    @Test
    void tamperedMessageAndMismatchedRequestNonceAreRejectedWithoutConsumingChallenge()
            throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge challenge = requestSignedChallenge(walletAddress, keyPair);

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                challenge.walletAddress(),
                                challenge.message() + "\nTampered",
                                challenge.signature(),
                                challenge.challengeHandle(),
                                challenge.nonce()
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                challenge.walletAddress(),
                                challenge.message(),
                                challenge.signature(),
                                challenge.challengeHandle(),
                                "0000000000000000"
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void everySiweFieldIsBoundToTheIssuedChallenge() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge challenge = requestSignedChallenge(walletAddress, keyPair);

        List<String> tamperedMessages = List.of(
                challenge.message().replaceFirst(
                        "^.* wants you to sign in",
                        "attacker.example wants you to sign in"
                ),
                challenge.message().replace(
                        walletAddress,
                        "0x0000000000000000000000000000000000000001"
                ),
                challenge.message().replace(
                        "URI: https://api.u2511175.nyat.app",
                        "URI: https://attacker.example"
                ),
                challenge.message().replace("Chain ID: 1", "Chain ID: 5"),
                challenge.message().replace(
                        "Nonce: " + challenge.nonce(),
                        "Nonce: replaced-nonce"
                ),
                challenge.message().replace(
                        "Issued At: ",
                        "Issued At: 2000-01-01T00:00:00Z\n# Issued At: "
                ),
                challenge.message().replace(
                        "Expiration Time: ",
                        "Expiration Time: 2099-01-01T00:00:00Z\n# Expiration Time: "
                )
        );

        for (String tamperedMessage : tamperedMessages) {
            String tamperedSignature = signMessage(tamperedMessage, keyPair);
            mockMvc.perform(post("/api/auth/web3/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody(
                                    challenge.walletAddress(),
                                    tamperedMessage,
                                    tamperedSignature,
                                    challenge.challengeHandle(),
                                    challenge.nonce(),
                                    1
                            )))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));
        }

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void requestChainIdMustMatchTheSupportedSiweChain() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge challenge = requestSignedChallenge(walletAddress, keyPair);

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                challenge.walletAddress(),
                                challenge.message(),
                                challenge.signature(),
                                challenge.challengeHandle(),
                                challenge.nonce(),
                                5
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void theSameSignedChallengeCanOnlyBeConsumedOnceConcurrently() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge challenge = requestSignedChallenge(walletAddress, keyPair);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Integer> statuses = new ArrayList<>();

        try {
            var futures = List.of(1, 2).stream()
                    .map(ignored -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return mockMvc.perform(post("/api/auth/web3/verify")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(challenge.requestBody()))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            futures.forEach(future -> {
                try {
                    statuses.add(future.get(10, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("Concurrent Web3 verification failed", e);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 401);
        assertThat(web3NonceRepository.findByWalletAddress(walletAddress)).isEmpty();
        assertThat(loginMethodRepository.findByAuthProviderAndProviderUserId(
                UserLoginMethod.AuthProvider.WEB3,
                walletAddress
        )).isPresent();
    }

    @Test
    void secondNonceRequestCannotOverwriteAnActiveChallengeForTheSameWallet()
            throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge firstChallenge = requestSignedChallenge(walletAddress, keyPair);

        mockMvc.perform(get("/api/auth/web3/nonce/{wallet}", walletAddress))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("WEB3_CHALLENGE_CAPACITY"));

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstChallenge.requestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void concurrentNonceGenerationCreatesOnlyOneActiveChallenge() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<MvcResult> responses = new ArrayList<>();

        try {
            var futures = List.of(1, 2).stream()
                    .map(ignored -> executor.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                        return mockMvc.perform(
                                get("/api/auth/web3/nonce/{wallet}", walletAddress)
                        ).andReturn();
                    }))
                    .toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            futures.forEach(future -> {
                try {
                    responses.add(future.get(10, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("Concurrent Web3 nonce generation failed", e);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        assertThat(responses)
                .extracting(response -> response.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 429);
        var stored = web3NonceRepository.findByWalletAddress(walletAddress)
                .orElseThrow();
        JsonNode successfulResponse = responses.stream()
                .filter(response -> response.getResponse().getStatus() == 200)
                .map(response -> {
                    try {
                        return responseJson(response);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                })
                .findFirst()
                .orElseThrow();
        assertThat(stored.getMessage())
                .isEqualTo(successfulResponse.path("message").asText());
        assertThat(stored.getChallengeHandle())
                .isEqualTo(successfulResponse.path("challengeHandle").asText());

        String signature = signMessage(stored.getMessage(), keyPair);
        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody(
                                walletAddress,
                                stored.getMessage(),
                                signature,
                                stored.getChallengeHandle(),
                                stored.getNonce()
                        )))
                .andExpect(status().isOk());
    }

    @Test
    void expiredNonceLookupRemovesTheChallenge() {
        String walletAddress = "0x0000000000000000000000000000000000000001";
        web3NonceService.saveNonce(
                walletAddress,
                "ExpiredNonceValue123",
                "expired-message",
                "127.0.0.1",
                Instant.now().minusSeconds(1)
        );

        assertThat(web3NonceService.getNonce(walletAddress)).isNull();
        assertThat(web3NonceRepository.findByWalletAddress(walletAddress)).isEmpty();
    }

    @Test
    void expiredNonceIsRejectedAndRemoved() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge challenge = requestSignedChallenge(walletAddress, keyPair);
        var nonce = web3NonceRepository.findByWalletAddress(walletAddress).orElseThrow();
        nonce.setExpiresAt(Instant.now().minusSeconds(1));
        web3NonceRepository.saveAndFlush(nonce);

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        assertThat(web3NonceRepository.findByWalletAddress(walletAddress)).isEmpty();
    }

    private SignedChallenge requestSignedChallenge(String walletAddress, ECKeyPair keyPair)
            throws Exception {
        MvcResult nonceResult = mockMvc.perform(
                        get("/api/auth/web3/nonce/{wallet}", walletAddress)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn").value(300))
                .andReturn();
        JsonNode nonceBody = responseJson(nonceResult);
        String challengeHandle = nonceBody.path("challengeHandle").asText();
        String nonce = nonceBody.path("nonce").asText();
        String message = nonceBody.path("message").asText();
        assertThat(challengeHandle).isNotBlank();
        Instant messageExpiration = message.lines()
                .filter(line -> line.startsWith("Expiration Time: "))
                .map(line -> line.substring("Expiration Time: ".length()))
                .map(Instant::parse)
                .findFirst()
                .orElseThrow();
        assertThat(web3NonceRepository.findByWalletAddress(walletAddress))
                .isPresent()
                .get()
                .extracting(storedNonce -> storedNonce.getExpiresAt())
                .isEqualTo(messageExpiration);
        String signature = signMessage(message, keyPair);

        return new SignedChallenge(
                requestBody(
                        walletAddress,
                        message,
                        signature,
                        challengeHandle,
                        nonce
                ),
                walletAddress,
                message,
                signature,
                challengeHandle,
                nonce
        );
    }

    private String requestBody(
            String walletAddress,
            String message,
            String signature,
            String challengeHandle,
            String nonce) throws Exception {
        return requestBody(
                walletAddress,
                message,
                signature,
                challengeHandle,
                nonce,
                1
        );
    }

    private String requestBody(
            String walletAddress,
            String message,
            String signature,
            String challengeHandle,
            String nonce,
            Integer chainId) throws Exception {
        return objectMapper.writeValueAsString(new Web3Request(
                walletAddress,
                message,
                signature,
                challengeHandle,
                nonce,
                chainId
        ));
    }

    private String signMessage(String message, ECKeyPair keyPair) {
        Sign.SignatureData signatureData = Sign.signPrefixedMessage(
                message.getBytes(StandardCharsets.UTF_8),
                keyPair
        );
        byte[] signature = new byte[65];
        System.arraycopy(signatureData.getR(), 0, signature, 0, 32);
        System.arraycopy(signatureData.getS(), 0, signature, 32, 32);
        signature[64] = signatureData.getV()[0];
        return Numeric.toHexString(signature);
    }

    private JsonNode responseJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private void assertTokenCookies(MvcResult result) {
        assertCookie(result, "accessToken", 3600);
        assertCookie(result, "refreshToken", 604800);
    }

    private void assertCookie(MvcResult result, String name, int maxAge) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertThat(cookie).as(name).isNotNull();
        assertThat(cookie.isHttpOnly()).as(name + " HttpOnly").isTrue();
        assertThat(cookie.getSecure()).as(name + " Secure").isFalse();
        assertThat(cookie.getPath()).as(name + " Path").isEqualTo("/");
        assertThat(cookie.getMaxAge()).as(name + " Max-Age").isEqualTo(maxAge);
        assertThat(cookie.getAttribute("SameSite"))
                .as(name + " SameSite")
                .isEqualTo("Lax");
    }

    private record SignedChallenge(
            String requestBody,
            String walletAddress,
            String message,
            String signature,
            String challengeHandle,
            String nonce) {
    }

    private record Web3Request(
            String walletAddress,
            String message,
            String signature,
            String challengeHandle,
            String nonce,
            Integer chainId
    ) {
    }
}
