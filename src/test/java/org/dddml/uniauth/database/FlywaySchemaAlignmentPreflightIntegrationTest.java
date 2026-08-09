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

class FlywaySchemaAlignmentPreflightIntegrationTest extends PostgreSqlIntegrationTest {

    @Test
    void versionThreeUpgradesToVersionSeven() throws Exception {
        withVersionThreeDatabase((databaseName, jdbcUrl) -> {
            Flyway latest = latestFlyway(jdbcUrl);

            assertThat(latest.migrate().migrationsExecuted).isEqualTo(4);
            assertThat(latest.info().current()).isNotNull();
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("7");
        });
    }

    @Test
    void versionFourRejectsNullUserRuntimeFields() throws Exception {
        assertVersionFourRejected(
                """
                INSERT INTO users (
                    id, username, email, email_verified, enabled, created_at, updated_at
                ) VALUES (
                    '00000000-0000-0000-0000-000000000101',
                    'v4-null-user',
                    'v4-null-user@example.invalid',
                    NULL,
                    true,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                );
                """,
                "V4 preflight: user runtime fields must be non-null"
        );
    }

    @Test
    void versionFourRejectsNullWeb3NonceCreationTime() throws Exception {
        assertVersionFourRejected(
                """
                INSERT INTO web3_nonces (
                    id, wallet_address, nonce, expires_at, created_at
                ) VALUES (
                    '00000000-0000-0000-0000-000000000111',
                    '0x0000000000000000000000000000000000000111',
                    'v4-null-created-at',
                    CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                    NULL
                );
                """,
                "V4 preflight: Web3 nonce creation time must be non-null"
        );
    }

    @Test
    void versionFourRejectsInvalidEmailVerificationState() throws Exception {
        assertVersionFourRejected(
                """
                INSERT INTO email_verification_codes (
                    id, created_at, email, expires_at, is_used, metadata,
                    purpose, retry_count, updated_at, verification_code
                ) VALUES (
                    '00000000-0000-0000-0000-000000000121',
                    CURRENT_TIMESTAMP,
                    'v4-invalid-email-state@example.invalid',
                    CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                    false,
                    NULL,
                    'REGISTRATION',
                    -1,
                    CURRENT_TIMESTAMP,
                    '123456'
                );
                """,
                "V4 preflight: email verification state must be non-null and nonnegative"
        );
    }

    @Test
    void versionFourRejectsInvalidTokenBlacklistState() throws Exception {
        assertVersionFourRejected(
                """
                INSERT INTO token_blacklist (
                    id, jti, token_type, user_id, expires_at, blacklisted_at, reason
                ) VALUES (
                    '00000000-0000-0000-0000-000000000131',
                    'v4-invalid-token-type',
                    'UNKNOWN',
                    NULL,
                    CURRENT_TIMESTAMP + INTERVAL '1 hour',
                    CURRENT_TIMESTAMP,
                    'preflight fixture'
                );
                """,
                "V4 preflight: token blacklist state is invalid"
        );
    }

    private void assertVersionFourRejected(String setupSql, String expectedMessage)
            throws Exception {
        withVersionThreeDatabase((databaseName, jdbcUrl) -> {
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
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("3");
        });
    }

    private void withVersionThreeDatabase(DatabaseTest test) throws Exception {
        String databaseName = "uniauth_v4_" + UUID.randomUUID().toString().replace("-", "");
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
            Flyway versionThree = Flyway.configure()
                    .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                    .locations("classpath:db/migration/postgresql")
                    .table("uniauth_flyway_schema_history")
                    .target(MigrationVersion.fromVersion("3"))
                    .cleanDisabled(true)
                    .load();
            assertThat(versionThree.migrate().migrationsExecuted).isEqualTo(3);
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
