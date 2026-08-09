package org.dddml.uniauth.controller;

import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.Web3AuthService;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.AuthenticationLogoutService;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.CredentialAuthenticationService;
import org.dddml.uniauth.service.RegistrationService;
import org.dddml.uniauth.service.RecentAuthenticationService;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenValidationService;
import org.dddml.uniauth.service.TokenIntrospectionService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.service.AuthenticationCredentialResolver;
import org.dddml.uniauth.config.IntrospectionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
                mock(RegistrationService.class),
                mock(CredentialAuthenticationService.class),
                mock(TokenIssuanceFacade.class),
                mock(AuthenticationLogoutService.class),
                mock(AuthRateLimiter.class)
        );
        ApiAuthController apiAuthController = new ApiAuthController(
                mock(UserRepository.class),
                mock(AuthCookieService.class),
                mock(AuthenticationLogoutService.class),
                mock(UserLoginMethodRepository.class)
        );
        OAuth2TokenController oAuth2TokenController =
                new OAuth2TokenController(
                        mock(JwtTokenService.class),
                        mock(TokenIntrospectionService.class),
                        mock(IntrospectionProperties.class),
                        mock(AuthRateLimiter.class),
                        mock(AuthCookieService.class)
                );
        Web3AuthController web3AuthController = new Web3AuthController(
                mock(Web3AuthService.class),
                mock(TokenValidationService.class),
                mock(TokenIssuanceFacade.class),
                mock(UserService.class),
                mock(AuthenticationCredentialResolver.class),
                mock(RecentAuthenticationService.class),
                mock(AuthRateLimiter.class)
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
        assertNotFound(get(
                "/api/auth/web3/status/0x0000000000000000000000000000000000000000"
        ));
        mockMvc.perform(delete("/api/auth/web3/nonce/0x0000000000000000000000000000000000000000"))
                .andExpect(status().isMethodNotAllowed());
    }

    private void assertNotFound(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request).andExpect(status().isNotFound());
    }
}
