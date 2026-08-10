package org.dddml.email.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class EmailSharedSchemaFlywayBootstrapIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16.13")
            .withDatabaseName("email_shared_guard_test")
            .withUsername("email_shared_guard")
            .withPassword("email_shared_guard");

    @Test
    void rejectsAnIncompleteUniAuthPeerBeforeCreatingHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id character varying(36) PRIMARY KEY)");

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), true)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a complete UniAuth schema");

        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.email_service_flyway_schema_history')",
            String.class
        )).isNull();
    }

    @Test
    void rejectsExistingEmailRelationsBeforeCreatingHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE email_queue (id bigint PRIMARY KEY)");

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), true)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("managed relations already exist")
            .hasMessageContaining("email_queue");

        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.email_service_flyway_schema_history')",
            String.class
        )).isNull();
    }

    @Test
    void dedicatedLayoutRejectsANonEmptySchemaBeforeCreatingHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE unrelated_table (id bigint PRIMARY KEY)");

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), false)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL_DATABASE_LAYOUT=shared-uniauth");

        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.email_service_flyway_schema_history')",
            String.class
        )).isNull();
    }

    @Test
    void existingSharedSchemaRequiresExplicitLayoutAndACompletePeer() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        migrateUniAuth(dataSource);
        EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), true);

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), false)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL_DATABASE_LAYOUT=shared-uniauth");

        jdbc.execute("DROP TABLE user_authorities");

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), true)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a complete UniAuth schema")
            .hasMessageContaining("user_authorities");
    }

    @Test
    void existingSharedSchemaRevalidatesCurrentPeerRelations() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        migrateUniAuth(dataSource);
        EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), true);

        jdbc.execute("DROP TABLE oauth2_binding_intents");

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), true)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a complete UniAuth schema")
            .hasMessageContaining("oauth2_binding_intents");
    }

    @Test
    void existingEmailHistoryRejectsUniAuthRelationsWithoutPeerHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), false);
        jdbc.execute("CREATE TABLE users (id character varying(36) PRIMARY KEY)");

        assertThatThrownBy(() ->
            EmailSharedSchemaFlywayBootstrap.migrate(emailFlyway(dataSource), false)
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("relations exist without")
            .hasMessageContaining("users");
    }

    @Test
    void rejectsNonExactUniAuthPeerHistoryBeforeCreatingHistory() {
        for (String invalidHistory : java.util.List.of(
                "unknown-version",
                "duplicate-version",
                "repeatable",
                "unsuccessful")) {
            DataSource dataSource = newDatabase();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            migrateUniAuth(dataSource);

            switch (invalidHistory) {
                case "unknown-version" -> addHistoryRow(
                    jdbc,
                    "uniauth_flyway_schema_history",
                    "9",
                    "unexpected migration",
                    "SQL",
                    "V9__unexpected_migration.sql",
                    true
                );
                case "duplicate-version" -> addHistoryRow(
                    jdbc,
                    "uniauth_flyway_schema_history",
                    "1",
                    "duplicate migration",
                    "SQL",
                    "V1__duplicate_migration.sql",
                    true
                );
                case "repeatable" -> addHistoryRow(
                    jdbc,
                    "uniauth_flyway_schema_history",
                    null,
                    "unexpected repeatable",
                    "SQL",
                    "R__unexpected_repeatable.sql",
                    true
                );
                case "unsuccessful" -> addHistoryRow(
                    jdbc,
                    "uniauth_flyway_schema_history",
                    "7",
                    "failed migration",
                    "SQL",
                    "V7__failed_migration.sql",
                    false
                );
                default -> throw new AssertionError(
                    "Unhandled history fixture: " + invalidHistory
                );
            }

            String expectedMessage = switch (invalidHistory) {
                case "duplicate-version" ->
                    "must contain exactly one successful SQL version 1";
                case "unsuccessful" -> "unsuccessful migration";
                default -> "unexpected migration rows";
            };
            assertThatThrownBy(() ->
                EmailSharedSchemaFlywayBootstrap.migrate(
                    emailFlyway(dataSource),
                    true
                )
            )
                .as(invalidHistory)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);

            assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.email_service_flyway_schema_history')",
                String.class
            )).isNull();
        }
    }

    private Flyway emailFlyway(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .table("email_service_flyway_schema_history")
            .defaultSchema("public")
            .schemas("public")
            .baselineOnMigrate(false)
            .baselineVersion("0")
            .cleanDisabled(true)
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .validateOnMigrate(true)
            .outOfOrder(false)
            .group(true)
            .load();
    }

    private void migrateUniAuth(DataSource dataSource) {
        Path migrations = Path.of(
            System.getProperty(
                "uniauth.migrations.dir",
                "../../src/main/resources/db/migration/postgresql"
            )
        ).toAbsolutePath().normalize();

        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + migrations)
            .table("uniauth_flyway_schema_history")
            .defaultSchema("public")
            .schemas("public")
            .baselineOnMigrate(false)
            .baselineVersion("0")
            .cleanDisabled(true)
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .validateOnMigrate(true)
            .outOfOrder(false)
            .group(true)
            .load()
            .migrate();
    }

    private void addHistoryRow(
            JdbcTemplate jdbc,
            String historyTable,
            String version,
            String description,
            String type,
            String script,
            boolean success) {
        jdbc.update("""
            INSERT INTO %s (
                installed_rank,
                version,
                description,
                type,
                script,
                checksum,
                installed_by,
                execution_time,
                success
            )
            SELECT
                COALESCE(MAX(installed_rank), 0) + 1,
                ?,
                ?,
                ?,
                ?,
                0,
                CURRENT_USER,
                0,
                ?
            FROM %s
            """.formatted(historyTable, historyTable),
            version,
            description,
            type,
            script,
            success
        );
    }

    private DataSource newDatabase() {
        String database = "email_shared_guard_"
            + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE DATABASE \"" + database + "\"");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl().replace(
                "/email_shared_guard_test",
                "/" + database
            ),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    private DataSource adminDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }
}
