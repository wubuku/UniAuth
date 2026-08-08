package org.dddml.uniauth.database;

import org.dddml.uniauth.UniAuthApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = UniAuthApplication.class)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UniAuthSharedSchemaApplicationContextIntegrationTest {

    private static final Path KEY_DIRECTORY = createKeyDirectory();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("shared_email_uniauth_test")
            .withUsername("shared_schema_test")
            .withPassword("shared_schema_test");

    @DynamicPropertySource
    static void configureSharedDatabase(DynamicPropertyRegistry registry) {
        migrateEmailServiceFirst();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name",
            () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform",
            () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.session.jdbc.initialize-schema", () -> "never");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations",
            () -> "classpath:db/migration/postgresql");
        registry.add("spring.flyway.table",
            () -> "uniauth_flyway_schema_history");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.flyway.baseline-on-migrate", () -> "false");
        registry.add("spring.flyway.baseline-version", () -> "0");
        registry.add("spring.flyway.clean-disabled", () -> "true");
        registry.add("jwt.rsa.key-file",
            () -> KEY_DIRECTORY.resolve("signing-key.ser").toString());
        registry.add(
            "spring.security.oauth2.client.registration.google.client-id",
            () -> "test-google"
        );
        registry.add(
            "spring.security.oauth2.client.registration.google.client-secret",
            () -> "test-google-secret"
        );
        registry.add(
            "spring.security.oauth2.client.registration.github.client-id",
            () -> "test-github"
        );
        registry.add(
            "spring.security.oauth2.client.registration.github.client-secret",
            () -> "test-github-secret"
        );
        registry.add(
            "spring.security.oauth2.client.registration.x.client-id",
            () -> "test-x"
        );
        registry.add(
            "spring.security.oauth2.client.registration.x.client-secret",
            () -> "test-x-secret"
        );
    }

    @Autowired
    private Flyway uniAuthFlyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsOnAnExistingEmailPublicSchemaAndKeepsIndependentHistory() {
        assertThat(uniAuthFlyway.info().current()).isNotNull();
        assertThat(uniAuthFlyway.info().current().getVersion().toString())
            .isEqualTo("5");
        assertThat(uniAuthFlyway.migrate().migrationsExecuted).isZero();

        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM uniauth_flyway_schema_history
            WHERE type = 'BASELINE'
              AND version = '0'
              AND success
            """,
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM uniauth_flyway_schema_history
            WHERE type = 'SQL'
              AND version IN ('1', '2', '3', '4', '5')
              AND success
            """,
            Integer.class
        )).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM email_service_flyway_schema_history
            WHERE success
              AND version IN ('1', '2', '3')
            """,
            Integer.class
        )).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_regclass('public.users')",
            String.class
        )).isEqualTo("users");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_regclass('public.email_queue')",
            String.class
        )).isEqualTo("email_queue");
    }

    @AfterAll
    static void removeKeyDirectory() throws IOException {
        Files.deleteIfExists(KEY_DIRECTORY.resolve("signing-key.ser"));
        Files.deleteIfExists(KEY_DIRECTORY);
    }

    private static void migrateEmailServiceFirst() {
        Path migrations = Path.of(
            "reference/email-service/src/main/resources/db/migration/postgresql"
        ).toAbsolutePath().normalize();

        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
            )
            .locations("filesystem:" + migrations)
            .table("email_service_flyway_schema_history")
            .defaultSchema("public")
            .schemas("public")
            .baselineOnMigrate(false)
            .baselineVersion("0")
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load()
            .migrate();
    }

    private static Path createKeyDirectory() {
        try {
            return Files.createTempDirectory("uniauth-shared-schema-key-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
