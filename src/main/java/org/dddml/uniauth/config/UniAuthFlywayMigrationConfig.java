package org.dddml.uniauth.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class UniAuthFlywayMigrationConfig {

    private static final String SCHEMA = "public";
    private static final String HISTORY_TABLE = "uniauth_flyway_schema_history";
    private static final String PEER_HISTORY_TABLE =
            "email_service_flyway_schema_history";
    private static final String MIGRATION_LOCATION =
            "classpath:db/migration/postgresql";
    private static final long SHARED_SCHEMA_LOCK_KEY = -632082753896054443L;

    private static final Set<String> MANAGED_RELATIONS = Set.of(
            "users",
            "users_email_key",
            "users_pkey",
            "users_username_key",
            "idx_users_email",
            "idx_users_username",
            "user_login_methods",
            "user_login_methods_pkey",
            "idx_login_methods_primary",
            "idx_login_methods_provider",
            "idx_login_methods_user_id",
            "idx_user_login_methods_chain_id",
            "idx_user_login_methods_web3_nonce",
            "uk_local_username",
            "uk_provider_user",
            "uk_user_login_provider",
            "uk_login_methods_one_primary",
            "web3_nonces",
            "web3_nonces_pkey",
            "idx_web3_nonces_expires_at",
            "idx_web3_nonces_wallet_address",
            "web3_nonces_wallet_address_key",
            "email_verification_codes",
            "email_verification_codes_pkey",
            "idx_email_verification_pending_lookup",
            "idx_email_verification_email_created_at",
            "idx_email_verification_expires_at",
            "uk_email_challenge_one_active",
            "idx_email_challenge_handle_lookup",
            "idx_email_challenge_delivery",
            "email_delivery_outbox",
            "email_delivery_outbox_pkey",
            "email_delivery_outbox_challenge_key",
            "email_delivery_outbox_idempotency_key",
            "idx_email_delivery_outbox_pending",
            "auth_rate_limits",
            "auth_rate_limits_pkey",
            "idx_auth_rate_limits_expires_at",
            "security_events",
            "security_events_pkey",
            "idx_security_events_subject_created",
            "token_families",
            "token_families_pkey",
            "idx_token_families_user_active",
            "idx_token_families_expires_at",
            "oauth2_binding_intents",
            "oauth2_binding_intents_pkey",
            "uk_oauth2_binding_intents_state_hash",
            "idx_oauth2_binding_intents_expiry",
            "idx_oauth2_binding_intents_user_active",
            "web3_challenge_counters",
            "web3_challenge_counters_pkey",
            "uk_web3_nonces_challenge_handle",
            "uk_users_canonical_contact_email",
            "user_authorities",
            "user_authorities_pkey",
            "token_blacklist",
            "token_blacklist_jti_key",
            "token_blacklist_pkey",
            "idx_expires_at",
            "idx_jti",
            "idx_token_blacklist_expires_at",
            "idx_token_blacklist_jti",
            "spring_session",
            "spring_session_pkey",
            "spring_session_ix1",
            "spring_session_ix2",
            "spring_session_ix3",
            "spring_session_attributes",
            "spring_session_attributes_pkey"
    );

    private static final Set<String> REQUIRED_PEER_RELATIONS = Set.of(
            PEER_HISTORY_TABLE,
            "email_queue",
            "email_queue_id_seq",
            "email_logs",
            "email_logs_id_seq",
            "uk_email_queue_idempotency_key"
    );

    private static final Set<String> PEER_MANAGED_RELATIONS = Set.of(
            "email_queue",
            "email_queue_id_seq",
            "email_logs",
            "email_logs_id_seq",
            "uk_email_queue_idempotency_key"
    );

    private static final List<String> REQUIRED_PEER_VERSIONS =
            List.of("1", "2", "3", "4", "5");

    @Bean
    FlywayMigrationStrategy uniAuthFlywayMigrationStrategy(Environment environment) {
        if (!Boolean.TRUE.equals(
                environment.getProperty("spring.flyway.enabled", Boolean.class))) {
            throw new IllegalStateException(
                    "SPRING_FLYWAY_ENABLED must be exactly true"
            );
        }
        return UniAuthFlywayMigrationConfig::migrate;
    }

    static void migrate(Flyway flyway) {
        validateConfiguration(flyway.getConfiguration());

        DataSource dataSource = flyway.getConfiguration().getDataSource();
        try (Connection lockConnection = dataSource.getConnection()) {
            acquireSharedSchemaLock(lockConnection);
            try {
                migrateWhileLocked(flyway, lockConnection);
            } finally {
                releaseSharedSchemaLock(lockConnection);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to validate the UniAuth shared PostgreSQL schema",
                    exception
            );
        }
    }

    private static void migrateWhileLocked(Flyway flyway, Connection connection)
            throws SQLException {
        if (relationExists(connection, HISTORY_TABLE)) {
            if (relationExists(connection, PEER_HISTORY_TABLE)) {
                validateEmailPeer(connection);
            } else {
                rejectEmailPeerWithoutHistory(connection);
            }
            flyway.migrate();
            return;
        }
        if (!schemaHasRelations(connection)) {
            flyway.migrate();
            return;
        }

        Set<String> collisions = existingRelations(connection, MANAGED_RELATIONS);
        if (!collisions.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing automatic UniAuth baseline because managed relations "
                            + "already exist: " + String.join(", ", collisions)
            );
        }

        validateEmailPeer(connection);
        flyway.baseline();
        try {
            flyway.migrate();
        } catch (RuntimeException exception) {
            removeBaselineOnlyHistory(connection);
            throw exception;
        }
    }

    private static void validateConfiguration(
            org.flywaydb.core.api.configuration.Configuration configuration) {
        if (!HISTORY_TABLE.equals(configuration.getTable())) {
            throw new IllegalStateException(
                    "SPRING_FLYWAY_TABLE must be exactly " + HISTORY_TABLE
            );
        }
        if (!SCHEMA.equals(configuration.getDefaultSchema())
                || configuration.getSchemas().length != 1
                || !SCHEMA.equals(configuration.getSchemas()[0])) {
            throw new IllegalStateException(
                    "UniAuth Flyway must manage only the public schema"
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

    private static void validateEmailPeer(Connection connection) throws SQLException {
        Set<String> missingRelations = new java.util.TreeSet<>();
        for (String relation : REQUIRED_PEER_RELATIONS) {
            if (!relationExists(connection, relation)) {
                missingRelations.add(relation);
            }
        }
        if (!missingRelations.isEmpty()) {
            throw new IllegalStateException(
                    "Non-empty public schema is not a complete email-service schema; "
                            + "missing: " + String.join(", ", missingRelations)
            );
        }

        requireSuccessfulPeerHistory(connection);
    }

    private static void rejectEmailPeerWithoutHistory(Connection connection)
            throws SQLException {
        Set<String> peerRelations = existingRelations(
                connection,
                PEER_MANAGED_RELATIONS
        );
        if (!peerRelations.isEmpty()) {
            throw new IllegalStateException(
                    "Email-service relations exist without "
                            + PEER_HISTORY_TABLE + ": "
                            + String.join(", ", peerRelations)
            );
        }
    }

    private static void requireSuccessfulPeerHistory(Connection connection)
            throws SQLException {
        if (queryCount(
                connection,
                "SELECT count(*) FROM public." + PEER_HISTORY_TABLE
                        + " WHERE success IS NOT TRUE"
        ) != 0) {
            throw new IllegalStateException(
                    "Email-service Flyway history contains an unsuccessful migration"
            );
        }
        for (String version : REQUIRED_PEER_VERSIONS) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FROM public." + PEER_HISTORY_TABLE
                            + " WHERE success IS TRUE AND type = 'SQL' "
                            + "AND version = ?")) {
                statement.setString(1, version);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    if (resultSet.getInt(1) != 1) {
                        throw new IllegalStateException(
                                "Email-service Flyway history must contain exactly "
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
                    "Email-service Flyway history contains unexpected migration rows"
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
        Set<String> existing = new java.util.TreeSet<>();
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
