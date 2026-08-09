package org.dddml.email.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class EmailSharedSchemaFlywayBootstrap {

    private static final String SCHEMA = "public";
    private static final String HISTORY_TABLE =
        "email_service_flyway_schema_history";
    private static final String PEER_HISTORY_TABLE =
        "uniauth_flyway_schema_history";
    private static final String MIGRATION_LOCATION =
        "classpath:db/migration/postgresql";
    private static final long SHARED_SCHEMA_LOCK_KEY = -632082753896054443L;

    private static final Set<String> MANAGED_RELATIONS = Set.of(
        "email_queue",
        "email_queue_id_seq",
        "email_queue_pkey",
        "idx_email_queue_status",
        "idx_email_queue_priority_created",
        "idx_email_queue_next_retry",
        "idx_email_queue_status_updated",
        "idx_email_queue_recovery",
        "uk_email_queue_idempotency_key",
        "email_logs",
        "email_logs_id_seq",
        "email_logs_pkey",
        "idx_email_logs_status",
        "idx_email_logs_recipient",
        "idx_email_logs_sent_time",
        "idx_email_logs_queue_id",
        "idx_email_logs_status_sent_time"
    );

    private static final Set<String> REQUIRED_PEER_RELATIONS = Set.of(
        PEER_HISTORY_TABLE,
        "users",
        "user_login_methods",
        "web3_nonces",
        "email_verification_codes",
        "email_delivery_outbox",
        "auth_rate_limits",
        "security_events",
        "user_authorities",
        "token_blacklist",
        "token_families",
        "oauth2_binding_intents",
        "web3_challenge_counters",
        "spring_session",
        "spring_session_attributes"
    );

    private static final Set<String> PEER_MANAGED_RELATIONS = Set.of(
        "users",
        "user_login_methods",
        "web3_nonces",
        "email_verification_codes",
        "email_delivery_outbox",
        "auth_rate_limits",
        "security_events",
        "user_authorities",
        "token_blacklist",
        "token_families",
        "oauth2_binding_intents",
        "web3_challenge_counters",
        "spring_session",
        "spring_session_attributes"
    );

    private static final List<String> REQUIRED_PEER_VERSIONS =
        List.of("1", "2", "3", "4", "5", "6", "7", "8");

    private EmailSharedSchemaFlywayBootstrap() {
    }

    static void migrate(Flyway flyway, boolean sharedUniAuthLayout) {
        validateConfiguration(flyway.getConfiguration());

        DataSource dataSource = flyway.getConfiguration().getDataSource();
        try (Connection lockConnection = dataSource.getConnection()) {
            acquireSharedSchemaLock(lockConnection);
            try {
                migrateWhileLocked(flyway, lockConnection, sharedUniAuthLayout);
            } finally {
                releaseSharedSchemaLock(lockConnection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Unable to validate the email-service shared PostgreSQL schema",
                exception
            );
        }
    }

    private static void migrateWhileLocked(
            Flyway flyway,
            Connection connection,
            boolean sharedUniAuthLayout) throws SQLException {
        if (relationExists(connection, HISTORY_TABLE)) {
            if (relationExists(connection, PEER_HISTORY_TABLE)) {
                if (!sharedUniAuthLayout) {
                    throw new IllegalStateException(
                        "Existing UniAuth schema requires "
                            + "EMAIL_DATABASE_LAYOUT=shared-uniauth"
                    );
                }
                validateUniAuthPeer(connection);
            } else {
                rejectUniAuthPeerWithoutHistory(connection);
            }
            flyway.migrate();
            return;
        }
        if (!schemaHasRelations(connection)) {
            flyway.migrate();
            return;
        }
        if (!sharedUniAuthLayout) {
            throw new IllegalStateException(
                "Non-empty public schema requires EMAIL_DATABASE_LAYOUT=shared-uniauth"
            );
        }

        Set<String> collisions = existingRelations(connection, MANAGED_RELATIONS);
        if (!collisions.isEmpty()) {
            throw new IllegalStateException(
                "Refusing automatic email-service baseline because managed relations "
                    + "already exist: " + String.join(", ", collisions)
            );
        }

        validateUniAuthPeer(connection);
        flyway.baseline();
        try {
            flyway.migrate();
        } catch (RuntimeException exception) {
            removeBaselineOnlyHistory(connection);
            throw exception;
        }
    }

    private static void rejectUniAuthPeerWithoutHistory(Connection connection)
            throws SQLException {
        Set<String> peerRelations = existingRelations(
            connection,
            PEER_MANAGED_RELATIONS
        );
        if (!peerRelations.isEmpty()) {
            throw new IllegalStateException(
                "UniAuth relations exist without " + PEER_HISTORY_TABLE + ": "
                    + String.join(", ", peerRelations)
            );
        }
    }

    private static void validateConfiguration(Configuration configuration) {
        if (!HISTORY_TABLE.equals(configuration.getTable())) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_TABLE must be exactly " + HISTORY_TABLE
            );
        }
        if (!SCHEMA.equals(configuration.getDefaultSchema())
                || configuration.getSchemas().length != 1
                || !SCHEMA.equals(configuration.getSchemas()[0])) {
            throw new IllegalStateException(
                "Email-service Flyway must manage only the public schema"
            );
        }
        if (configuration.isBaselineOnMigrate()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_BASELINE_ON_MIGRATE must be exactly false"
            );
        }
        if (!MigrationVersion.fromVersion("0")
                .equals(configuration.getBaselineVersion())) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_BASELINE_VERSION must be exactly 0"
            );
        }
        if (!configuration.isCleanDisabled()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_CLEAN_DISABLED must be exactly true"
            );
        }
        if (!configuration.isFailOnMissingLocations()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS must be exactly true"
            );
        }
        if (configuration.getLocations().length != 1
                || !MIGRATION_LOCATION.equals(
                    configuration.getLocations()[0].getDescriptor()
                )) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_LOCATIONS must be exactly " + MIGRATION_LOCATION
            );
        }
        if (!configuration.isValidateMigrationNaming()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_VALIDATE_MIGRATION_NAMING must be exactly true"
            );
        }
        if (!configuration.isValidateOnMigrate()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_VALIDATE_ON_MIGRATE must be exactly true"
            );
        }
        if (configuration.isOutOfOrder()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_OUT_OF_ORDER must be exactly false"
            );
        }
        if (!configuration.isGroup()) {
            throw new IllegalStateException(
                "SPRING_FLYWAY_GROUP must be exactly true"
            );
        }
    }

    private static void validateUniAuthPeer(Connection connection)
            throws SQLException {
        Set<String> missingRelations = new TreeSet<>();
        for (String relation : REQUIRED_PEER_RELATIONS) {
            if (!relationExists(connection, relation)) {
                missingRelations.add(relation);
            }
        }
        if (!missingRelations.isEmpty()) {
            throw new IllegalStateException(
                "Non-empty public schema is not a complete UniAuth schema; missing: "
                    + String.join(", ", missingRelations)
            );
        }

        if (queryCount(
            connection,
            "SELECT count(*) FROM public." + PEER_HISTORY_TABLE
                + " WHERE success IS NOT TRUE"
        ) != 0) {
            throw new IllegalStateException(
                "UniAuth Flyway history contains an unsuccessful migration"
            );
        }
        for (String version : REQUIRED_PEER_VERSIONS) {
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM public." + PEER_HISTORY_TABLE
                    + " WHERE success IS TRUE AND type = 'SQL' AND version = ?")) {
                statement.setString(1, version);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) != 1) {
                        throw new IllegalStateException(
                            "UniAuth Flyway history must contain exactly "
                                + "one successful SQL version " + version
                        );
                    }
                }
            }
        }

        int baselineRows = queryCount(
            connection,
            "SELECT count(*) FROM public." + PEER_HISTORY_TABLE
                + " WHERE success IS TRUE AND type = 'BASELINE' "
                + "AND version = '0'"
        );
        int successfulRows = queryCount(
            connection,
            "SELECT count(*) FROM public." + PEER_HISTORY_TABLE
                + " WHERE success IS TRUE"
        );
        if (baselineRows > 1
                || successfulRows != REQUIRED_PEER_VERSIONS.size() + baselineRows) {
            throw new IllegalStateException(
                "UniAuth Flyway history contains unexpected migration rows"
            );
        }
    }

    private static void removeBaselineOnlyHistory(Connection connection)
            throws SQLException {
        if (!relationExists(connection, HISTORY_TABLE)
                || !existingRelations(connection, MANAGED_RELATIONS).isEmpty()) {
            return;
        }
        int totalRows = queryCount(
            connection,
            "SELECT count(*) FROM public." + HISTORY_TABLE
        );
        int baselineRows = queryCount(
            connection,
            "SELECT count(*) FROM public." + HISTORY_TABLE
                + " WHERE type = 'BASELINE' AND version = '0' "
                + "AND success IS TRUE"
        );
        if (totalRows == 1 && baselineRows == 1) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE public." + HISTORY_TABLE);
            }
        }
    }

    private static boolean schemaHasRelations(Connection connection)
            throws SQLException {
        return queryCount(
            connection,
            """
            SELECT count(*)
            FROM pg_class relation
            JOIN pg_namespace namespace
              ON namespace.oid = relation.relnamespace
            WHERE namespace.nspname = 'public'
              AND relation.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
            """
        ) > 0;
    }

    private static boolean relationExists(Connection connection, String relation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, SCHEMA + "." + relation);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private static Set<String> existingRelations(
            Connection connection,
            Set<String> relations) throws SQLException {
        Set<String> existing = new TreeSet<>();
        for (String relation : relations) {
            if (relationExists(connection, relation)) {
                existing.add(relation);
            }
        }
        return existing;
    }

    private static int queryCount(Connection connection, String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void acquireSharedSchemaLock(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_advisory_lock(?)")) {
            statement.setLong(1, SHARED_SCHEMA_LOCK_KEY);
            statement.execute();
        }
    }

    private static void releaseSharedSchemaLock(Connection connection)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT pg_advisory_unlock(?)")) {
            statement.setLong(1, SHARED_SCHEMA_LOCK_KEY);
            statement.execute();
        }
    }
}
