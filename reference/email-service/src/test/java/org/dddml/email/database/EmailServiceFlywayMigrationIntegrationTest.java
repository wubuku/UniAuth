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
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("email_service_migration_test")
            .withUsername("email_migration_test")
            .withPassword("email_migration_test");

    @Test
    void freshMigrationCreatesV2ConstraintsIndexesAndHistory() throws Exception {
        String schema = newSchema();

        flyway(schema, null).migrate();

        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM email_service_flyway_schema_history
                WHERE success = TRUE
                  AND version IN ('1', '2')
                """)).isEqualTo(2);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM pg_constraint
                WHERE conname IN (
                    'chk_email_queue_retry_bounds',
                    'fk_email_logs_queue'
                )
                  AND connamespace = current_schema()::regnamespace
                """)).isEqualTo(2);
            assertThat(queryInt(statement, """
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND indexname IN (
                    'idx_email_queue_recovery',
                    'idx_email_logs_status_sent_time'
                )
                """)).isEqualTo(2);
        }
    }

    @Test
    void v1ToV2UpgradePreservesRowsAndNullsLogReferenceWhenQueueIsDeleted() throws Exception {
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
                    'COMPLETED', 5, 0, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
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
            statement.executeUpdate("DELETE FROM email_queue WHERE id = 1");
            assertThat(queryInt(statement,
                "SELECT COUNT(*) FROM email_logs WHERE queue_id IS NULL")).isEqualTo(1);
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
