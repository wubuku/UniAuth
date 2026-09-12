package org.dddml.uniauth.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1:8080",
            "http://user:password@127.0.0.1:8080",
            "http://127.0.0.1",
            "http://127.0.0.1:0",
            "http://127.0.0.1:65536",
            "http://127.0.0.1:8080/path",
            "http://127.0.0.1:8080?query=value",
            "http://127.0.0.1:8080#fragment"
    })
    void rejectsUnsafeProxyUrls(String proxyUrl) {
        contextRunner
                .withPropertyValues(
                        "app.oauth2.http.proxy-url=" + proxyUrl
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    @Test
    void directModeOverridesAnInheritedProxyUrl() {
        contextRunner
                .withPropertyValues(
                        "app.oauth2.http.proxy-mode=DIRECT",
                        "app.oauth2.http.proxy-url=socks5://user:secret@127.0.0.1:8080/path"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OAuth2HttpClientProperties properties = context.getBean(
                            OAuth2HttpClientProperties.class
                    );
                    assertThat(OAuth2HttpClientConfig.proxySelector(
                            properties,
                            null
                    ).select(URI.create(
                            "https://provider.example/token"
                    ))).containsExactly(Proxy.NO_PROXY);
                });
    }

    @Test
    void requiresProxyUrlInHttpMode() {
        contextRunner
                .withPropertyValues(
                        "app.oauth2.http.proxy-mode=HTTP"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(
                                    BindValidationException.class
                            );
                });
    }

    @Test
    void directModeUsesNoProxySelector() {
        OAuth2HttpClientProperties properties =
                new OAuth2HttpClientProperties();
        properties.setProxyMode(
                OAuth2HttpClientProperties.ProxyMode.DIRECT
        );

        assertThat(OAuth2HttpClientConfig.proxySelector(properties, null)
                .select(URI.create("https://provider.example/token")))
                .containsExactly(Proxy.NO_PROXY);
    }

    @Test
    void autoModeWithoutEnvironmentProxyKeepsSystemSelection() {
        OAuth2HttpClientProperties properties =
                new OAuth2HttpClientProperties();

        assertThat(OAuth2HttpClientConfig.proxySelector(properties, null))
                .isNull();
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

    @Test
    void tokenClientUsesTheConfiguredHttpProxy() throws Exception {
        try (ServerSocket proxySocket = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<String> proxyRequest = executor.submit(() -> {
                try (var socket = proxySocket.accept();
                        var reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.US_ASCII
                        ))) {
                    String requestLine = reader.readLine();
                    int contentLength = 0;
                    String header;
                    while ((header = reader.readLine()) != null
                            && !header.isEmpty()) {
                        if (header.regionMatches(
                                true,
                                0,
                                "Content-Length:",
                                0,
                                "Content-Length:".length()
                        )) {
                            contentLength = Integer.parseInt(
                                    header.substring(
                                            "Content-Length:".length()
                                    ).trim()
                            );
                        }
                    }
                    char[] body = new char[contentLength];
                    int offset = 0;
                    while (offset < body.length) {
                        int count = reader.read(body, offset, body.length - offset);
                        if (count < 0) {
                            break;
                        }
                        offset += count;
                    }
                    byte[] responseBody = """
                            {
                              "access_token": "proxied-provider-token",
                              "token_type": "Bearer",
                              "expires_in": 300
                            }
                            """.getBytes(StandardCharsets.UTF_8);
                    String responseHeaders = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + responseBody.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    socket.getOutputStream().write(
                            responseHeaders.getBytes(StandardCharsets.US_ASCII)
                    );
                    socket.getOutputStream().write(responseBody);
                    socket.getOutputStream().flush();
                    return requestLine + "\n" + new String(
                            body,
                            0,
                            offset
                    );
                }
            });
            try {
                contextRunner
                        .withPropertyValues(
                                "app.oauth2.http.proxy-mode=HTTP",
                                "app.oauth2.http.proxy-url=http://127.0.0.1:"
                                        + proxySocket.getLocalPort()
                        )
                        .run(context -> {
                            @SuppressWarnings("unchecked")
                            OAuth2AccessTokenResponseClient<
                                    OAuth2AuthorizationCodeGrantRequest> client =
                                    context.getBean(
                                            "oauth2AuthorizationCodeTokenResponseClient",
                                            OAuth2AccessTokenResponseClient.class
                                    );

                            var response = client.getTokenResponse(
                                    authorizationCodeGrantRequest(
                                            "http://provider.invalid/token"
                                    )
                            );

                            assertThat(response.getAccessToken().getTokenValue())
                                    .isEqualTo("proxied-provider-token");
                        });
                assertThat(proxyRequest.get(3, TimeUnit.SECONDS))
                        .startsWith(
                                "POST http://provider.invalid/token HTTP/1.1"
                        )
                        .contains("grant_type=authorization_code")
                        .contains("code=authorization-code");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private OAuth2AuthorizationCodeGrantRequest
            authorizationCodeGrantRequest(int tokenPort) {
        return authorizationCodeGrantRequest(
                "http://127.0.0.1:" + tokenPort + "/token"
        );
    }

    private OAuth2AuthorizationCodeGrantRequest
            authorizationCodeGrantRequest(String tokenUri) {
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
                        .tokenUri(tokenUri)
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
