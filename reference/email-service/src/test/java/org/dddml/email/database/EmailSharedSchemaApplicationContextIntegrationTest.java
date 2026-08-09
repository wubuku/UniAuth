package org.dddml.email.database;

import org.dddml.email.EmailServiceApplication;
import org.flywaydb.core.Flyway;
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

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = EmailServiceApplication.class,
    properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.fail-on-missing-locations=true",
        "spring.flyway.locations=classpath:db/migration/postgresql",
        "spring.flyway.table=email_service_flyway_schema_history",
        "spring.flyway.default-schema=public",
        "spring.flyway.schemas=public",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.baseline-version=0",
        "spring.flyway.clean-disabled=true",
        "spring.flyway.validate-migration-naming=true",
        "spring.flyway.validate-on-migrate=true",
        "spring.flyway.out-of-order=false",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=2525",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false",
        "spring.mail.properties.mail.smtp.ssl.enable=false",
        "spring.mail.properties.mail.smtp.ssl.checkserveridentity=true",
        "app.email.database-layout=shared-uniauth",
        "app.mail.from-email=no-reply@example.test",
        "app.mail.from-name=Shared Schema Test",
        "app.mail.recovery.enabled=false",
        "app.mail.rate-limit.enabled=false"
    }
)
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmailSharedSchemaApplicationContextIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("uniauth_test")
            .withUsername("shared_schema_test")
            .withPassword("shared_schema_test");

    @DynamicPropertySource
    static void configureSharedDatabase(DynamicPropertyRegistry registry) {
        migrateUniAuthFirst();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Flyway emailFlyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startsOnAnExistingUniAuthPublicSchemaAndKeepsIndependentHistory() {
        assertThat(emailFlyway.info().current()).isNotNull();
        assertThat(emailFlyway.info().current().getVersion().toString())
            .isEqualTo("5");
        assertThat(emailFlyway.migrate().migrationsExecuted).isZero();

        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM email_service_flyway_schema_history
            WHERE type = 'BASELINE'
              AND version = '0'
              AND success
            """,
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM email_service_flyway_schema_history
            WHERE type = 'SQL'
              AND version IN ('1', '2', '3', '4', '5')
              AND success
            """,
            Integer.class
        )).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
            """
            SELECT count(*)
            FROM uniauth_flyway_schema_history
            WHERE type = 'SQL'
              AND version IN ('1', '2', '3', '4', '5', '6')
              AND success
            """,
            Integer.class
        )).isEqualTo(6);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_regclass('public.users')",
            String.class
        )).isEqualTo("users");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT to_regclass('public.email_queue')",
            String.class
        )).isEqualTo("email_queue");
    }

    private static void migrateUniAuthFirst() {
        Path migrations = Path.of(
            System.getProperty(
                "uniauth.migrations.dir",
                "../../src/main/resources/db/migration/postgresql"
            )
        ).toAbsolutePath().normalize();

        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
            )
            .locations("filesystem:" + migrations)
            .table("uniauth_flyway_schema_history")
            .defaultSchema("public")
            .schemas("public")
            .baselineOnMigrate(false)
            .baselineVersion("0")
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .outOfOrder(false)
            .group(true)
            .load()
            .migrate();
    }
}
