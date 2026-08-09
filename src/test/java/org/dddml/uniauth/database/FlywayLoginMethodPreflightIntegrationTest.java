package org.dddml.uniauth.database;

import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayLoginMethodPreflightIntegrationTest extends PostgreSqlIntegrationTest {

    @Test
    void versionOneUpgradesToLatestVersion() throws Exception {
        withVersionOneDatabase((databaseName, jdbcUrl) -> {
            Flyway latest = latestFlyway(jdbcUrl);

            assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
            assertThat(latest.info().current()).isNotNull();
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("7");
        });
    }

    @Test
    void versionTwoRejectsUsersWithoutExactlyOnePrimaryLoginMethod() throws Exception {
        assertVersionTwoRejected(
                """
                INSERT INTO users (id, username, email)
                VALUES ('00000000-0000-0000-0000-000000000001',
                        'missing-primary',
                        'missing-primary@example.invalid');
                INSERT INTO user_login_methods (
                    id, user_id, auth_provider, local_username, is_primary, is_verified
                ) VALUES (
                    '00000000-0000-0000-0000-000000000002',
                    '00000000-0000-0000-0000-000000000001',
                    'LOCAL',
                    'missing-primary',
                    false,
                    false
                );
                """,
                "V2 preflight: every user must have exactly one primary login method"
        );
    }

    @Test
    void versionTwoRejectsNullRuntimeFields() throws Exception {
        assertVersionTwoRejected(
                """
                INSERT INTO users (id, username, email)
                VALUES ('00000000-0000-0000-0000-000000000011',
                        'null-runtime-field',
                        'null-runtime-field@example.invalid');
                INSERT INTO user_login_methods (
                    id, user_id, auth_provider, local_username, is_primary, is_verified
                ) VALUES (
                    '00000000-0000-0000-0000-000000000012',
                    '00000000-0000-0000-0000-000000000011',
                    'LOCAL',
                    'null-runtime-field',
                    true,
                    NULL
                );
                """,
                "V2 preflight: login method runtime fields must be non-null"
        );
    }

    @Test
    void versionTwoRejectsUnknownProviders() throws Exception {
        assertVersionTwoRejected(
                """
                INSERT INTO users (id, username, email)
                VALUES ('00000000-0000-0000-0000-000000000021',
                        'unknown-provider',
                        'unknown-provider@example.invalid');
                INSERT INTO user_login_methods (
                    id, user_id, auth_provider, provider_user_id, is_primary, is_verified
                ) VALUES (
                    '00000000-0000-0000-0000-000000000022',
                    '00000000-0000-0000-0000-000000000021',
                    'SAML',
                    'unknown-provider-subject',
                    true,
                    true
                );
                """,
                "V2 preflight: unknown auth_provider value"
        );
    }

    @Test
    void versionTwoRejectsInvalidProviderFieldShapes() throws Exception {
        assertVersionTwoRejected(
                """
                INSERT INTO users (id, username, email)
                VALUES ('00000000-0000-0000-0000-000000000031',
                        'invalid-provider-shape',
                        'invalid-provider-shape@example.invalid');
                INSERT INTO user_login_methods (
                    id, user_id, auth_provider, is_primary, is_verified
                ) VALUES (
                    '00000000-0000-0000-0000-000000000032',
                    '00000000-0000-0000-0000-000000000031',
                    'GITHUB',
                    true,
                    true
                );
                """,
                "V2 preflight: invalid login method provider field shape"
        );
    }

    private void assertVersionTwoRejected(String setupSql, String expectedMessage)
            throws Exception {
        withVersionOneDatabase((databaseName, jdbcUrl) -> {
            try (var connection = DriverManager.getConnection(
                    jdbcUrl,
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            ); var statement = connection.createStatement()) {
                statement.execute(setupSql);
            }

            Flyway latest = latestFlyway(jdbcUrl);
            assertThatThrownBy(latest::migrate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining(expectedMessage);
            assertThat(latest.info().current()).isNotNull();
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("1");
        });
    }

    private void withVersionOneDatabase(DatabaseTest test) throws Exception {
        String databaseName = "uniauth_v2_" + UUID.randomUUID().toString().replace("-", "");
        String adminUrl = postgresUrl("postgres");
        String jdbcUrl = postgresUrl(databaseName);

        try (var connection = DriverManager.getConnection(
                adminUrl,
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }

        try {
            Flyway versionOne = Flyway.configure()
                    .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration/postgresql")
                    .table("uniauth_flyway_schema_history")
                    .target(MigrationVersion.fromVersion("1"))
                    .cleanDisabled(true)
                    .load();
            assertThat(versionOne.migrate().migrationsExecuted).isEqualTo(1);
            test.run(databaseName, jdbcUrl);
        } finally {
            try (var connection = DriverManager.getConnection(
                    adminUrl,
                    POSTGRES.getUsername(),
                    POSTGRES.getPassword()
            ); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            }
        }
    }

    private Flyway latestFlyway(String jdbcUrl) {
        return Flyway.configure()
                .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/postgresql")
                .table("uniauth_flyway_schema_history")
                .cleanDisabled(true)
                .load();
    }

    private String postgresUrl(String databaseName) {
        return "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/"
                + databaseName;
    }

    @FunctionalInterface
    private interface DatabaseTest {
        void run(String databaseName, String jdbcUrl) throws Exception;
    }
}
