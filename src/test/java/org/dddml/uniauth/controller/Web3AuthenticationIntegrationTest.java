package org.dddml.uniauth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.Web3NonceRepository;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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
    private Web3NonceRepository web3NonceRepository;

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
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String userId = responseJson(firstLogin).path("userId").asText();
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletAddress").value(walletAddress))
                .andExpect(jsonPath("$.isBound").value(true));

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
        MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "web3-bind-local",
                                  "email": "web3-bind-local@example.invalid",
                                  "password": "web3-bind-password",
                                  "displayName": "Web3 Bind"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String userId = responseJson(registerResult).path("id").asText();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .param("username", username)
                        .param("password", password))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode loginBody = responseJson(loginResult);
        String accessToken = loginBody.path("accessToken").asText();
        String refreshToken = loginBody.path("refreshToken").asText();

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

        ECKeyPair secondKeyPair = Keys.createEcKeyPair();
        String secondWallet = "0x" + Keys.getAddress(secondKeyPair.getPublicKey());
        SignedChallenge secondChallenge = requestSignedChallenge(secondWallet, secondKeyPair);
        mockMvc.perform(post("/api/auth/web3/bind")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondChallenge.requestBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BINDING_FAILED"));
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
                                "different-nonce"
                        )))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(challenge.requestBody()))
                .andExpect(status().isOk());
    }

    @Test
    void latestNonceSupersedesEarlierChallengeForTheSameWallet() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());
        SignedChallenge firstChallenge = requestSignedChallenge(walletAddress, keyPair);
        SignedChallenge latestChallenge = requestSignedChallenge(walletAddress, keyPair);

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstChallenge.requestBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/auth/web3/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(latestChallenge.requestBody()))
                .andExpect(status().isOk());
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
        String nonce = nonceBody.path("nonce").asText();
        String message = nonceBody.path("message").asText();
        String signature = signMessage(message, keyPair);

        return new SignedChallenge(
                requestBody(walletAddress, message, signature, nonce),
                walletAddress,
                message,
                signature,
                nonce
        );
    }

    private String requestBody(
            String walletAddress,
            String message,
            String signature,
            String nonce) throws Exception {
        return objectMapper.writeValueAsString(new Web3Request(
                walletAddress,
                message,
                signature,
                nonce,
                1
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

    private record SignedChallenge(
            String requestBody,
            String walletAddress,
            String message,
            String signature,
            String nonce) {
    }

    private record Web3Request(
            String walletAddress,
            String message,
            String signature,
            String nonce,
            Integer chainId
    ) {
    }
}
