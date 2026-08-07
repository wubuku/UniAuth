package org.dddml.uniauth.database;

import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayFailureRecoveryIntegrationTest extends PostgreSqlIntegrationTest {

    @Test
    void checksumChangeIsRejectedAfterMigrationWasApplied() throws Exception {
        Path migrationDirectory = Files.createTempDirectory("uniauth-flyway-checksum-");
        String schema = schemaName("checksum");
        try {
            Path migration = migrationDirectory.resolve("V1__create_checksum_probe.sql");
            Files.writeString(migration, "CREATE TABLE checksum_probe (id INTEGER PRIMARY KEY);\n");

            Flyway original = flyway(schema, migrationDirectory);
            assertThat(original.migrate().migrationsExecuted).isEqualTo(1);

            Files.writeString(
                    migration,
                    "CREATE TABLE checksum_probe (id INTEGER PRIMARY KEY, label TEXT);\n"
            );
            Flyway changed = flyway(schema, migrationDirectory);

            assertThatThrownBy(changed::validate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum");
        } finally {
            dropSchema(schema);
            deleteDirectory(migrationDirectory);
        }
    }

    @Test
    void failedTransactionalMigrationCanBeCorrectedAndAppliedForward() throws Exception {
        Path migrationDirectory = Files.createTempDirectory("uniauth-flyway-recovery-");
        String schema = schemaName("recovery");
        try {
            Files.writeString(
                    migrationDirectory.resolve("V1__create_recovery_probe.sql"),
                    "CREATE TABLE recovery_probe (id INTEGER PRIMARY KEY);\n"
            );
            Path secondMigration =
                    migrationDirectory.resolve("V2__extend_recovery_probe.sql");
            Files.writeString(secondMigration, "ALTER TABLE recovery_probe ADD COLUMN;\n");

            Flyway broken = flyway(schema, migrationDirectory);
            assertThatThrownBy(broken::migrate).isInstanceOf(FlywayException.class);

            assertThat(historyCount(schema, "1", true)).isEqualTo(1);
            assertThat(historyCount(schema, "2", true)).isZero();

            Files.writeString(
                    secondMigration,
                    "ALTER TABLE recovery_probe ADD COLUMN label TEXT;\n"
            );
            Flyway corrected = flyway(schema, migrationDirectory);

            assertThat(corrected.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(corrected.info().current()).isNotNull();
            assertThat(corrected.info().current().getVersion().toString()).isEqualTo("2");
            assertThat(columnExists(schema, "recovery_probe", "label")).isTrue();
        } finally {
            dropSchema(schema);
            deleteDirectory(migrationDirectory);
        }
    }

    private Flyway flyway(String schema, Path migrationDirectory) {
        return Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("filesystem:" + migrationDirectory.toAbsolutePath())
                .schemas(schema)
                .defaultSchema(schema)
                .table("flyway_schema_history")
                .createSchemas(true)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load();
    }

    private int historyCount(String schema, String version, boolean success) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); var statement = connection.prepareStatement(
                "SELECT count(*) FROM " + schema
                        + ".flyway_schema_history WHERE version = ? AND success = ?"
        )) {
            statement.setString(1, version);
            statement.setBoolean(2, success);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private boolean columnExists(String schema, String table, String column) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); var statement = connection.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = ?
                      AND column_name = ?
                )
                """
        )) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private void dropSchema(String schema) throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private String schemaName(String prefix) {
        return "flyway_" + prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (Files.notExists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to clean Flyway test files", e);
                }
            });
        }
    }
}
