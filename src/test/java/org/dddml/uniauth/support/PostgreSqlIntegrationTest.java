package org.dddml.uniauth.support;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class PostgreSqlIntegrationTest {

    private static final Path TEST_DIRECTORY = createTestDirectory();

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("uniauth_test")
                    .withUsername("uniauth")
                    .withPassword("uniauth-test-password");

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.session.jdbc.initialize-schema", () -> "never");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/postgresql");
        registry.add("spring.flyway.table", () -> "uniauth_flyway_schema_history");
        registry.add("jwt.rsa.key-file",
                () -> TEST_DIRECTORY.resolve("signing-key.ser").toString());
        registry.add("spring.security.oauth2.client.registration.google.client-id",
                () -> "test-google");
        registry.add("spring.security.oauth2.client.registration.google.client-secret",
                () -> "test-google-secret");
        registry.add("spring.security.oauth2.client.registration.github.client-id",
                () -> "test-github");
        registry.add("spring.security.oauth2.client.registration.github.client-secret",
                () -> "test-github-secret");
        registry.add("spring.security.oauth2.client.registration.x.client-id", () -> "test-x");
        registry.add("spring.security.oauth2.client.registration.x.client-secret",
                () -> "test-x-secret");
    }

    @AfterAll
    static void removeTemporaryFiles() throws IOException {
        if (Files.notExists(TEST_DIRECTORY)) {
            return;
        }
        try (var paths = Files.walk(TEST_DIRECTORY)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to clean integration-test files", e);
                }
            });
        }
    }

    private static Path createTestDirectory() {
        try {
            return Files.createTempDirectory("uniauth-postgresql-test-");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
