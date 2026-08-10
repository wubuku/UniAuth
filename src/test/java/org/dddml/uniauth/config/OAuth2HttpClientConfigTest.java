package org.dddml.uniauth.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuth2HttpClientConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            RestTemplateAutoConfiguration.class,
                            OAuth2HttpClientConfig.class
                    )
                    .withPropertyValues(
                            "app.oauth2.http.connect-timeout-ms=250",
                            "app.oauth2.http.read-timeout-ms=250"
                    );

    @Test
    void createsTheBoundedOAuth2RestTemplate() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("oauth2RestTemplate");
            assertThat(context).hasBean("oauth2TokenRestTemplate");
            assertThat(context)
                    .hasBean("oauth2AuthorizationCodeTokenResponseClient");
        });
    }

    @Test
    void rejectsEffectivelyUnboundedTimeouts() {
        contextRunner
                .withPropertyValues(
                        "app.oauth2.http.read-timeout-ms=60001"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(BindValidationException.class);
                });
    }

    @Test
    void slowProviderResponsesHitTheConfiguredReadTimeout() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (var ignored = serverSocket.accept()) {
                    Thread.sleep(5_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // Client timeout and socket close are expected in this fixture.
                }
            });
            try {
                contextRunner.run(context -> {
                    RestTemplate restTemplate = context.getBean(
                            "oauth2RestTemplate",
                            RestTemplate.class
                    );
                    String url = "http://127.0.0.1:"
                            + serverSocket.getLocalPort()
                            + "/slow";

                    long startedAt = System.nanoTime();
                    assertThatThrownBy(() -> restTemplate.getForObject(
                            url,
                            String.class
                    )).isInstanceOf(ResourceAccessException.class);
                    assertThat(Duration.ofNanos(
                            System.nanoTime() - startedAt
                    )).isLessThan(Duration.ofSeconds(3));
                });
            } finally {
                server.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void slowTokenResponsesHitTheConfiguredReadTimeout() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (var ignored = serverSocket.accept()) {
                    Thread.sleep(5_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // Client timeout and socket close are expected in this fixture.
                }
            });
            try {
                contextRunner.run(context -> {
                    @SuppressWarnings("unchecked")
                    OAuth2AccessTokenResponseClient<
                            OAuth2AuthorizationCodeGrantRequest> client =
                            context.getBean(
                                    "oauth2AuthorizationCodeTokenResponseClient",
                                    OAuth2AccessTokenResponseClient.class
                            );

                    long startedAt = System.nanoTime();
                    assertThatThrownBy(() -> client.getTokenResponse(
                            authorizationCodeGrantRequest(
                                    serverSocket.getLocalPort()
                            )
                    )).isInstanceOf(OAuth2AuthorizationException.class);
                    assertThat(Duration.ofNanos(
                            System.nanoTime() - startedAt
                    )).isLessThan(Duration.ofSeconds(3));
                });
            } finally {
                server.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    @Test
    void tokenClientParsesAValidAuthorizationCodeResponse()
            throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/token", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            authorization.set(
                    exchange.getRequestHeaders().getFirst("Authorization")
            );
            byte[] response = """
                    {
                      "access_token": "provider-access-token",
                      "token_type": "Bearer",
                      "expires_in": 300
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json"
            );
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            contextRunner.run(context -> {
                @SuppressWarnings("unchecked")
                OAuth2AccessTokenResponseClient<
                        OAuth2AuthorizationCodeGrantRequest> client =
                        context.getBean(
                                "oauth2AuthorizationCodeTokenResponseClient",
                                OAuth2AccessTokenResponseClient.class
                        );

                var response = client.getTokenResponse(
                        authorizationCodeGrantRequest(server.getAddress()
                                .getPort())
                );

                assertThat(response.getAccessToken().getTokenValue())
                        .isEqualTo("provider-access-token");
                assertThat(requestBody.get())
                        .contains("grant_type=authorization_code")
                        .contains("code=authorization-code");
                assertThat(authorization.get()).startsWith("Basic ");
            });
        } finally {
            server.stop(0);
        }
    }

    private OAuth2AuthorizationCodeGrantRequest
            authorizationCodeGrantRequest(int tokenPort) {
        String redirectUri = "http://127.0.0.1/callback";
        ClientRegistration registration =
                ClientRegistration.withRegistrationId("slow-provider")
                        .clientId("client-id")
                        .clientSecret("client-secret")
                        .clientAuthenticationMethod(
                                ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                        )
                        .authorizationGrantType(
                                AuthorizationGrantType.AUTHORIZATION_CODE
                        )
                        .redirectUri(redirectUri)
                        .authorizationUri(
                                "https://provider.example/authorize"
                        )
                        .tokenUri(
                                "http://127.0.0.1:" + tokenPort + "/token"
                        )
                        .userInfoUri("https://provider.example/user")
                        .userNameAttributeName("sub")
                        .clientName("Slow provider")
                        .build();
        OAuth2AuthorizationRequest request =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri(
                                registration.getProviderDetails()
                                        .getAuthorizationUri()
                        )
                        .clientId(registration.getClientId())
                        .redirectUri(redirectUri)
                        .state("state")
                        .build();
        OAuth2AuthorizationResponse response =
                OAuth2AuthorizationResponse.success("authorization-code")
                        .redirectUri(redirectUri)
                        .state("state")
                        .build();
        return new OAuth2AuthorizationCodeGrantRequest(
                registration,
                new OAuth2AuthorizationExchange(request, response)
        );
    }
}
