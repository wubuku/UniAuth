package org.dddml.uniauth.config;

import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UniAuthSharedSchemaFlywayBootstrapIntegrationTest
        extends PostgreSqlIntegrationTest {

    @Test
    void rejectsAnIncompleteEmailPeerBeforeCreatingHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE email_queue (id bigint PRIMARY KEY)");

        assertThatThrownBy(() ->
            UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource))
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a complete email-service schema");

        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.uniauth_flyway_schema_history')",
            String.class
        )).isNull();
    }

    @Test
    void rejectsExistingUniAuthRelationsBeforeCreatingHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE users (id character varying(36) PRIMARY KEY)");

        assertThatThrownBy(() ->
            UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource))
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("managed relations already exist")
            .hasMessageContaining("users");

        assertThat(jdbc.queryForObject(
            "SELECT to_regclass('public.uniauth_flyway_schema_history')",
            String.class
        )).isNull();
    }

    @Test
    void existingSharedSchemaRevalidatesTheEmailPeer() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        migrateEmailService(dataSource);
        UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource));

        jdbc.execute("DROP TABLE email_logs");

        assertThatThrownBy(() ->
            UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource))
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not a complete email-service schema")
            .hasMessageContaining("email_logs");
    }

    @Test
    void existingUniAuthHistoryRejectsEmailRelationsWithoutPeerHistory() {
        DataSource dataSource = newDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource));
        jdbc.execute("CREATE TABLE email_queue (id bigint PRIMARY KEY)");

        assertThatThrownBy(() ->
            UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource))
        )
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("relations exist without")
            .hasMessageContaining("email_queue");
    }

    @Test
    void rejectsNonExactEmailPeerHistoryBeforeCreatingHistory() {
        for (String invalidHistory : List.of(
                "unknown-version",
                "duplicate-version",
                "repeatable",
                "unsuccessful")) {
            DataSource dataSource = newDatabase();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            migrateEmailService(dataSource);

            switch (invalidHistory) {
                case "unknown-version" -> addHistoryRow(
                    jdbc,
                    "email_service_flyway_schema_history",
                    "4",
                    "unexpected migration",
                    "SQL",
                    "V4__unexpected_migration.sql",
                    true
                );
                case "duplicate-version" -> addHistoryRow(
                    jdbc,
                    "email_service_flyway_schema_history",
                    "1",
                    "duplicate migration",
                    "SQL",
                    "V1__duplicate_migration.sql",
                    true
                );
                case "repeatable" -> addHistoryRow(
                    jdbc,
                    "email_service_flyway_schema_history",
                    null,
                    "unexpected repeatable",
                    "SQL",
                    "R__unexpected_repeatable.sql",
                    true
                );
                case "unsuccessful" -> addHistoryRow(
                    jdbc,
                    "email_service_flyway_schema_history",
                    "4",
                    "failed migration",
                    "SQL",
                    "V4__failed_migration.sql",
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
                UniAuthFlywayMigrationConfig.migrate(uniAuthFlyway(dataSource))
            )
                .as(invalidHistory)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);

            assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.uniauth_flyway_schema_history')",
                String.class
            )).isNull();
        }
    }

    @Test
    void rejectsUnsafeFlywaySchemaOwnerOverrides() {
        DataSource dataSource = newDatabase();
        List<ConfigurationOverride> overrides = List.of(
            new ConfigurationOverride(
                "SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS",
                configuration -> configuration.failOnMissingLocations(false)
            ),
            new ConfigurationOverride(
                "SPRING_FLYWAY_LOCATIONS",
                configuration -> configuration.locations("classpath:db/migration/other")
            ),
            new ConfigurationOverride(
                "SPRING_FLYWAY_VALIDATE_MIGRATION_NAMING",
                configuration -> configuration.validateMigrationNaming(false)
            ),
            new ConfigurationOverride(
                "SPRING_FLYWAY_VALIDATE_ON_MIGRATE",
                configuration -> configuration.validateOnMigrate(false)
            ),
            new ConfigurationOverride(
                "SPRING_FLYWAY_OUT_OF_ORDER",
                configuration -> configuration.outOfOrder(true)
            )
        );

        for (ConfigurationOverride override : overrides) {
            FluentConfiguration configuration = uniAuthConfiguration(dataSource);
            override.apply().accept(configuration);

            assertThatThrownBy(() ->
                UniAuthFlywayMigrationConfig.migrate(configuration.load())
            )
                .as(override.expectedMessage())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(override.expectedMessage());
        }
    }

    private Flyway uniAuthFlyway(DataSource dataSource) {
        return uniAuthConfiguration(dataSource).load();
    }

    private FluentConfiguration uniAuthConfiguration(DataSource dataSource) {
        return Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/postgresql")
            .table("uniauth_flyway_schema_history")
            .defaultSchema("public")
            .schemas("public")
            .baselineOnMigrate(false)
            .baselineVersion("0")
            .cleanDisabled(true)
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .validateOnMigrate(true)
            .outOfOrder(false);
    }

    private void migrateEmailService(DataSource dataSource) {
        Path migrations = Path.of(
            "reference/email-service/src/main/resources/db/migration/postgresql"
        ).toAbsolutePath().normalize();

        Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + migrations)
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
        String database = "uniauth_shared_guard_"
            + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE DATABASE \"" + database + "\"");

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl().replace("/uniauth_test", "/" + database),
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

    private record ConfigurationOverride(
            String expectedMessage,
            Consumer<FluentConfiguration> apply) {
    }
}
