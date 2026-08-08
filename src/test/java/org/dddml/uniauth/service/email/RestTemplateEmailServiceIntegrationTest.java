package org.dddml.uniauth.service.email;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.dddml.uniauth.config.EmailServiceClientConfig;
import org.dddml.uniauth.service.email.impl.RestTemplateEmailServiceImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig({
    RestTemplateAutoConfiguration.class,
    EmailServiceClientConfig.class,
    RestTemplateEmailServiceImpl.class
})
class RestTemplateEmailServiceIntegrationTest {

    private static final AtomicReference<String> LAST_API_KEY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();
    private static final HttpServer SERVER = startServer();

    @jakarta.annotation.Resource
    private EmailService emailService;

    @DynamicPropertySource
    static void configureClient(DynamicPropertyRegistry registry) {
        registry.add(
            "app.email.service.url",
            () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/mail/"
        );
        registry.add("app.email.service.timeout", () -> "150");
        registry.add("app.email.service.api-key", () -> "client-secret");
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Test
    void templateRequestUsesTheRealHttpClientAndConfiguredCredential() {
        EmailSendResult result = emailService.sendTemplateEmail(
            "user@example.test",
            "Verify",
            "email/email-verify",
            Map.of("verificationCode", "123456"),
            "VERIFICATION"
        );

        assertThat(result).isEqualTo(EmailSendResult.QUEUED);
        assertThat(LAST_API_KEY.get()).isEqualTo("client-secret");
        assertThat(LAST_REQUEST_BODY.get())
            .contains("\"to\":\"user@example.test\"")
            .contains("\"templateName\":\"email/email-verify\"")
            .contains("\"emailType\":\"VERIFICATION\"");
    }

    @Test
    void healthRequestUsesTheSameCredential() {
        assertThat(emailService.isAvailable()).isTrue();
        assertThat(LAST_API_KEY.get()).isEqualTo("client-secret");
    }

    @Test
    void readTimeoutKeepsTheExistingFailedResultSemantics() {
        EmailSendResult result = emailService.sendSimpleEmail(
            "slow@example.test",
            "Slow",
            "<p>Slow</p>"
        );

        assertThat(result).isEqualTo(EmailSendResult.FAILED);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mail/api/email/template", exchange -> {
                capture(exchange);
                respond(exchange, 200, "{\"success\":true,\"queueId\":1}");
            });
            server.createContext("/mail/api/email/health", exchange -> {
                capture(exchange);
                respond(exchange, 200, "{\"status\":\"UP\"}");
            });
            server.createContext("/mail/api/email/simple", exchange -> {
                capture(exchange);
                String body = LAST_REQUEST_BODY.get();
                if (body != null && body.contains("slow@example.test")) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                respond(exchange, 200, "{\"success\":true,\"queueId\":2}");
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start test HTTP server", exception);
        }
    }

    private static void capture(HttpExchange exchange) throws IOException {
        LAST_API_KEY.set(exchange.getRequestHeaders().getFirst("X-Email-Service-Key"));
        LAST_REQUEST_BODY.set(new String(
            exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8
        ));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
