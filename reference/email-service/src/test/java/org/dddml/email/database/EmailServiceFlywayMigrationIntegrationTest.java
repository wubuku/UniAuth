package org.dddml.email.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class EmailServiceFlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16.13")
            .withDatabaseName("email_service_migration_test")
            .withUsername("email_migration_test")
            .withPassword("email_migration_test");

    @Test
    void freshMigrationCreatesV5ConstraintsIndexesAndHistory() throws Exception {
        String schema = newSchema();

        flyway(schema, null).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version IN ('1', '2', '3', '4', '5')
                """)).isEqualTo(5);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname IN (
                    'chk_email_queue_retry_bounds',
                    'chk_email_queue_lifecycle_state',
                    'fk_email_logs_queue',
                    'chk_email_logs_content_redacted',
                    'chk_email_queue_terminal_payload_redacted'
                )
                  AND connamespace = current_schema()::regnamespace
                """)).isEqualTo(5);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'idx_email_queue_recovery',
                    'idx_email_logs_status_sent_time',
                    'uk_email_queue_idempotency_key'
                )
                """)).isEqualTo(3);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'email_queue'
                  AND column_name IN (
                    'idempotency_key',
                    'request_fingerprint'
                  )
                """)).isEqualTo(2);
        }
    }

    @Test
    void v1ToV5UpgradeNormalizesLifecycleMetadataAndPreservesRows() throws Exception {
        String schema = newSchema();
        flyway(schema, MigrationVersion.fromVersion("1")).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO email_queue (
                    recipient, subject, html_content, email_type, status, priority,
                    retry_count, max_retries, created_time, updated_time
                ) VALUES (
                    'upgrade@example.test', 'Upgrade', '<p>upgrade</p>', 'TEST',
                    'COMPLETED', 5, 0, 3,
                    CURRENT_TIMESTAMP - INTERVAL '2 minutes',
                    CURRENT_TIMESTAMP - INTERVAL '1 minute'
                )
                """);
            statement.executeUpdate("""
                UPDATE email_queue
                SET next_retry_time = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                    error_message = 'stale failure'
                WHERE id = 1
                """);
            statement.executeUpdate("""
                INSERT INTO email_logs (
                    queue_id, recipient, subject, status, sent_time, retry_count
                ) VALUES (
                    1, 'upgrade@example.test', 'Upgrade', 'SUCCESS',
                    CURRENT_TIMESTAMP, 0
                )
                """);
        }

        flyway(schema, null).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, "SELECT COUNT(*) FROM email_queue")).isEqualTo(1);
            assertThat(queryInt(statement, "SELECT COUNT(*) FROM email_logs")).isEqualTo(1);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_queue
                WHERE id = 1
                  AND status = 'COMPLETED'
                  AND processed_time = updated_time
                  AND next_retry_time IS NULL
                  AND error_message IS NULL
                """)).isEqualTo(1);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version IN ('1', '2', '3', '4', '5')
                """)).isEqualTo(5);
            statement.executeUpdate("DELETE FROM email_queue WHERE id = 1");
            assertThat(queryInt(statement,
                "SELECT COUNT(*) FROM email_logs WHERE queue_id IS NULL")).isEqualTo(1);
        }
    }

    @Test
    void v4ToV5UpgradeRedactsHistoricalTerminalPayloadsAndLogs() throws Exception {
        String schema = newSchema();
        flyway(schema, MigrationVersion.fromVersion("4")).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO email_queue (
                    recipient, subject, html_content, email_type, status, priority,
                    retry_count, max_retries, created_time, updated_time,
                    processed_time, error_message, metadata
                ) VALUES
                    (
                        'completed@example.test', 'Completed',
                        '<p>verification-code-314159</p>', 'VERIFICATION',
                        'COMPLETED', 5, 0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, NULL, '{"verificationCode":"314159"}'
                    ),
                    (
                        'failed@example.test', 'Failed',
                        '<p>reset-code-271828</p>', 'PASSWORD_RESET',
                        'FAILED', 5, 3, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, 'SMTP failed',
                        '{"verificationCode":"271828"}'
                    ),
                    (
                        'pending@example.test', 'Pending',
                        '<p>retry-code-161803</p>', 'PASSWORD_RESET',
                        'PENDING', 5, 1, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        NULL, NULL, '{"verificationCode":"161803"}'
                    )
                """);
            statement.executeUpdate("""
                INSERT INTO email_logs (
                    queue_id, recipient, subject, status, sent_time, retry_count,
                    email_content, email_type, mail_provider, send_method
                )
                SELECT
                    id, recipient, subject,
                    CASE WHEN status = 'COMPLETED' THEN 'SUCCESS' ELSE 'FAILED' END,
                    CURRENT_TIMESTAMP, retry_count, html_content, email_type,
                    'fixture', 'SCHEDULED'
                FROM email_queue
                WHERE status IN ('COMPLETED', 'FAILED')
                """);
        }

        flyway(schema, null).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_queue
                WHERE status IN ('COMPLETED', 'FAILED')
                  AND html_content = '<redacted/>'
                  AND metadata IS NULL
                """)).isEqualTo(2);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_queue
                WHERE status = 'PENDING'
                  AND html_content = '<p>retry-code-161803</p>'
                  AND metadata = '{"verificationCode":"161803"}'
                """)).isEqualTo(1);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_logs
                WHERE email_content IS NULL
                """)).isEqualTo(2);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname IN (
                    'chk_email_logs_content_redacted',
                    'chk_email_queue_terminal_payload_redacted'
                )
                  AND connamespace = current_schema()::regnamespace
                """)).isEqualTo(2);
        }
    }

    @Test
    void v2RejectsExistingRetryCountsAboveTheConfiguredMaximum() throws Exception {
        String schema = newSchema();
        flyway(schema, MigrationVersion.fromVersion("1")).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO email_queue (
                    recipient, subject, html_content, email_type, status, priority,
                    retry_count, max_retries, created_time, updated_time
                ) VALUES (
                    'invalid@example.test', 'Invalid', '<p>invalid</p>', 'TEST',
                    'PENDING', 5, 4, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate())
            .isInstanceOf(FlywayException.class);

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version = '2'
                """)).isZero();
        }
    }

    @Test
    void v2RejectsOrphanLogsUntilTheReferenceIsForwardFixed() throws Exception {
        String schema = newSchema();
        flyway(schema, MigrationVersion.fromVersion("1")).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO email_logs (
                    queue_id, recipient, subject, status, sent_time, retry_count
                ) VALUES (
                    999, 'orphan@example.test', 'Orphan', 'FAILED',
                    CURRENT_TIMESTAMP, 0
                )
                """);
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate())
            .isInstanceOf(FlywayException.class);

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version = '2'
                """)).isZero();
            assertThat(queryInt(statement,
                "SELECT COUNT(*) FROM email_logs WHERE queue_id = 999")).isEqualTo(1);
            statement.executeUpdate(
                "UPDATE email_logs SET queue_id = NULL WHERE queue_id = 999"
            );
        }

        flyway(schema, null).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version = '2'
                """)).isEqualTo(1);
            assertThat(queryInt(statement,
                "SELECT COUNT(*) FROM email_logs WHERE queue_id IS NULL")).isEqualTo(1);
        }
    }

    @Test
    void nonEmptySchemaWithoutHistoryDoesNotBaselineAutomatically() throws Exception {
        String schema = newSchema();
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE legacy_email_table (id BIGINT PRIMARY KEY)");
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("non-empty");
    }

    @Test
    void checksumMismatchPreservesHistoryAndDataUntilExplicitlyRepaired() throws Exception {
        String schema = newSchema();
        flyway(schema, null).migrate();

        int originalChecksum;
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            originalChecksum = queryInt(statement, """
                SELECT checksum
                FROM email_service_flyway_schema_history
                WHERE version = '1'
                """);
            statement.executeUpdate("""
                INSERT INTO email_queue (
                    recipient, subject, html_content, email_type, status, priority,
                    retry_count, max_retries, created_time, updated_time
                ) VALUES (
                    'checksum@example.test', 'Checksum', '<p>checksum</p>', 'TEST',
                    'PENDING', 5, 0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """);
            statement.executeUpdate("""
                UPDATE email_service_flyway_schema_history
                SET checksum = checksum + 1
                WHERE version = '1'
                """);
        }

        assertThatThrownBy(() -> flyway(schema, null).migrate())
            .isInstanceOf(FlywayException.class);

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, "SELECT COUNT(*) FROM email_queue")).isEqualTo(1);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version IN ('1', '2')
                """)).isEqualTo(2);
            assertThat(queryInt(statement, """
                SELECT checksum
                FROM email_service_flyway_schema_history
                WHERE version = '1'
                """)).isEqualTo(originalChecksum + 1);
            statement.executeUpdate("""
                UPDATE email_service_flyway_schema_history
                SET checksum = %d
                WHERE version = '1'
                """.formatted(originalChecksum));
        }

        flyway(schema, null).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, "SELECT COUNT(*) FROM email_queue")).isEqualTo(1);
            assertThat(queryInt(statement, """
                SELECT checksum
                FROM email_service_flyway_schema_history
                WHERE version = '1'
                """)).isEqualTo(originalChecksum);
        }
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .table("email_service_flyway_schema_history")
            .locations("classpath:db/migration/postgresql")
            .failOnMissingLocations(true)
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateMigrationNaming(true)
            .validateOnMigrate(true)
            .outOfOrder(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private String newSchema() throws Exception {
        String schema = "email_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        return schema;
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
