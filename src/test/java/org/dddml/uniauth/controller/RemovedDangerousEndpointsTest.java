package org.dddml.uniauth.controller;

import org.dddml.uniauth.config.EmailRegistrationProperties;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.EmailVerificationCodeService;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.service.Web3AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RemovedDangerousEndpointsTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthController authController = new AuthController(
                mock(UserService.class),
                mock(AuthenticationManager.class),
                mock(UserLoginMethodRepository.class),
                mock(PasswordEncoder.class),
                mock(JwtTokenService.class),
                mock(EmailRegistrationProperties.class),
                mock(EmailVerificationCodeService.class)
        );
        ApiAuthController apiAuthController = new ApiAuthController(mock(UserRepository.class));
        OAuth2TokenController oAuth2TokenController =
                new OAuth2TokenController(mock(JwtTokenService.class));
        Web3AuthController web3AuthController = new Web3AuthController(
                mock(Web3AuthService.class),
                mock(JwtTokenService.class)
        );

        mockMvc = standaloneSetup(
                authController,
                apiAuthController,
                oAuth2TokenController,
                web3AuthController
        ).build();
    }

    @Test
    void removedRoutesReturnNotFound() throws Exception {
        assertNotFound(get("/api/auth/check-user").param("username", "someone"));
        assertNotFound(get("/api/auth/user"));
        assertNotFound(get("/api/auth/generate-hash").param("password", "secret"));
        assertNotFound(post("/api/auth/create-test-user")
                .param("username", "someone")
                .param("password", "secret"));
        assertNotFound(post("/api/auth/reset-password")
                .param("username", "someone")
                .param("newPassword", "secret"));
        assertNotFound(post("/api/validate-google-token"));
        assertNotFound(post("/api/validate-github-token"));
        assertNotFound(post("/api/validate-x-token"));
        assertNotFound(get("/oauth2/introspect-test"));
        assertNotFound(post("/oauth2/validate").param("token", "token"));
        mockMvc.perform(delete("/api/auth/web3/nonce/0x0000000000000000000000000000000000000000"))
                .andExpect(status().isMethodNotAllowed());
    }

    private void assertNotFound(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isNotFound());
    }
}
