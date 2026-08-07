package org.dddml.uniauth.database;

import org.dddml.uniauth.UniAuthApplication;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayExistingSchemaBaselineIntegrationTest extends PostgreSqlIntegrationTest {

    @Test
    void approvedExistingSchemaCanBeBaselinedAndApplicationStartsWithoutSchemaMutation()
            throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        dataSource.setDriverClassName("org.postgresql.Driver");

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new ClassPathResource(
                            "db/migration/postgresql/V1__baseline_uniauth_auth_schema.sql"
                    )
            );
        }

        Flyway adoptionFlyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/postgresql")
                .table("uniauth_flyway_schema_history")
                .baselineVersion("1")
                .baselineDescription("Approved existing UniAuth auth schema")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();

        adoptionFlyway.baseline();
        assertThat(adoptionFlyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(adoptionFlyway.info().current()).isNotNull();
        assertThat(adoptionFlyway.info().current().getVersion().toString()).isEqualTo("4");

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT type
                FROM uniauth_flyway_schema_history
                WHERE version = '1'
                """,
                String.class
        )).isEqualTo("BASELINE");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND column_name = 'login_methods_revision'
                """,
                String.class
        )).isEqualTo("0");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_nullable || ':' || column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'email_verification_codes'
                  AND column_name = 'retry_count'
                """,
                String.class
        )).isEqualTo("NO:0");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.idx_email_verification_pending_lookup')",
                String.class
        )).isEqualTo("idx_email_verification_pending_lookup");

        Path keyDirectory = Files.createTempDirectory("uniauth-existing-schema-key-");
        Path keyFile = keyDirectory.resolve("signing-key.ser");
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(UniAuthApplication.class)
                             .profiles("test")
                             .run(applicationArguments(keyFile))) {
            Flyway runtimeFlyway = context.getBean(Flyway.class);
            assertThat(runtimeFlyway.migrate().migrationsExecuted).isZero();
            assertThat(runtimeFlyway.info().current()).isNotNull();
            assertThat(runtimeFlyway.info().current().getVersion().toString()).isEqualTo("4");
            assertThat(context.getBean(JdbcTemplate.class)
                    .queryForObject("SELECT count(*) FROM users", Long.class))
                    .isZero();
        } finally {
            Files.deleteIfExists(keyFile);
            Files.deleteIfExists(keyDirectory);
        }
    }

    private String[] applicationArguments(Path keyFile) {
        return new String[]{
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.sql.init.mode=never",
                "--spring.session.jdbc.initialize-schema=never",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/migration/postgresql",
                "--spring.flyway.table=uniauth_flyway_schema_history",
                "--jwt.rsa.key-file=" + keyFile,
                "--spring.security.oauth2.client.registration.google.client-id=test-google",
                "--spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "--spring.security.oauth2.client.registration.github.client-id=test-github",
                "--spring.security.oauth2.client.registration.github.client-secret=test-github-secret",
                "--spring.security.oauth2.client.registration.x.client-id=test-x",
                "--spring.security.oauth2.client.registration.x.client-secret=test-x-secret"
        };
    }
}
