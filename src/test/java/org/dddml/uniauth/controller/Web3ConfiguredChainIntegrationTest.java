package org.dddml.uniauth.controller;

import org.dddml.uniauth.dto.web3.Web3NonceResponse;
import org.dddml.uniauth.service.Web3AuthService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.web3.chain-id=137")
@ActiveProfiles("test")
class Web3ConfiguredChainIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private Web3AuthService web3AuthService;

    @Test
    void configuredChainIdIsEmbeddedInTheSignedMessageContract() throws Exception {
        ECKeyPair keyPair = Keys.createEcKeyPair();
        String walletAddress = "0x" + Keys.getAddress(keyPair.getPublicKey());

        Web3NonceResponse response = web3AuthService.generateNonce(
                walletAddress,
                "127.0.0.1"
        );

        assertThat(response.getChainId()).isEqualTo(137);
        assertThat(response.getMessage()).contains("Chain ID: 137");
    }
}
