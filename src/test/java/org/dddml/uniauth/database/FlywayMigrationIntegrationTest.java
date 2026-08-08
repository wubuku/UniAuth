package org.dddml.uniauth.database;

import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationIntegrationTest extends PostgreSqlIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "email_verification_codes",
            "spring_session",
            "spring_session_attributes",
            "token_blacklist",
            "uniauth_flyway_schema_history",
            "user_authorities",
            "user_login_methods",
            "users",
            "web3_nonces"
    );

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @SuppressWarnings("rawtypes")
    private SessionRepository sessionRepository;

    @Test
    void freshDatabaseMigratesToVersionFiveAndHibernateValidates() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
        assertThat(flyway.migrate().migrationsExecuted).isZero();

        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                ORDER BY table_name
                """,
                String.class
        );

        assertThat(tables).containsExactlyElementsOf(EXPECTED_TABLES);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.flyway_schema_history')",
                String.class
        )).isNull();
    }

    @Test
    void loginMethodSchemaEnforcesPrimaryAndProviderShapeInvariants() {
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND column_name = 'login_methods_revision'
                """,
                String.class
        )).isEqualTo("bigint");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'users'
                  AND column_name = 'login_methods_revision'
                """,
                String.class
        )).isEqualTo("NO");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'user_login_methods'
                  AND column_name = 'linked_at'
                """,
                String.class
        )).isEqualTo("timestamp with time zone");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'user_login_methods'
                  AND column_name = 'last_used_at'
                """,
                String.class
        )).isEqualTo("timestamp with time zone");

        String userId = UUID.randomUUID().toString();
        String localMethodId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                userId,
                "schema-" + userId,
                "schema-" + userId + "@example.invalid"
        );
        try {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT login_methods_revision FROM users WHERE id = ?",
                    Long.class,
                    userId
            )).isZero();
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE users SET login_methods_revision = -1 WHERE id = ?",
                    userId
            ))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "ck_users_login_methods_revision_nonnegative"
                    );

            jdbcTemplate.update(
                    """
                    INSERT INTO user_login_methods (
                        id, user_id, auth_provider, local_username,
                        is_primary, is_verified
                    ) VALUES (?, ?, 'LOCAL', ?, true, false)
                    """,
                    localMethodId,
                    userId,
                    "schema-local-" + userId
            );

            assertThatThrownBy(() -> jdbcTemplate.update(
                    """
                    INSERT INTO user_login_methods (
                        id, user_id, auth_provider, provider_user_id,
                        is_primary, is_verified
                    ) VALUES (?, ?, 'GITHUB', ?, true, true)
                    """,
                    UUID.randomUUID().toString(),
                    userId,
                    "schema-github-" + userId
            ))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_login_methods_one_primary");

            assertThatThrownBy(() -> jdbcTemplate.update(
                    """
                    INSERT INTO user_login_methods (
                        id, user_id, auth_provider, is_primary, is_verified
                    ) VALUES (?, ?, 'GOOGLE', false, true)
                    """,
                    UUID.randomUUID().toString(),
                    userId
            ))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_login_methods_provider_shape");
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void passwordlessLocalLoginMethodRemainsAValidPersistedShape() {
        String userId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                "INSERT INTO users (id, username, email) VALUES (?, ?, ?)",
                userId,
                "passwordless-" + userId,
                "passwordless-" + userId + "@example.invalid"
        );
        try {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO user_login_methods (
                        id, user_id, auth_provider, local_username,
                        local_password_hash, is_primary, is_verified
                    ) VALUES (?, ?, 'LOCAL', ?, NULL, true, true)
                    """,
                    UUID.randomUUID().toString(),
                    userId,
                    "passwordless-local-" + userId
            );
            assertThat(inserted).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void entityConstraintsDefaultsAndRepositoryIndexesAreAligned() {
        assertThat(columnDescriptor("users", "email_verified"))
                .isEqualTo("boolean:NO:false");
        assertThat(columnDescriptor("users", "enabled"))
                .isEqualTo("boolean:NO:true");
        assertThat(columnDescriptor("users", "created_at"))
                .isEqualTo("timestamp without time zone:NO:CURRENT_TIMESTAMP");
        assertThat(columnDescriptor("users", "updated_at"))
                .isEqualTo("timestamp without time zone:NO:CURRENT_TIMESTAMP");
        assertThat(columnDescriptor("web3_nonces", "created_at"))
                .isEqualTo("timestamp with time zone:NO:CURRENT_TIMESTAMP");
        assertThat(columnDescriptor("web3_nonces", "message"))
                .isEqualTo("text:NO:");
        assertThat(columnDescriptor("email_verification_codes", "is_used"))
                .isEqualTo("boolean:NO:false");
        assertThat(columnDescriptor("email_verification_codes", "retry_count"))
                .isEqualTo("integer:NO:0");
        assertThat(columnDescriptor("token_blacklist", "token_type"))
                .isEqualTo("character varying:NO:");
        assertThat(columnDescriptor("token_blacklist", "blacklisted_at"))
                .isEqualTo("timestamp without time zone:NO:CURRENT_TIMESTAMP");

        assertThat(constraintExists("ck_email_verification_retry_count_nonnegative"))
                .isTrue();
        assertThat(constraintExists("ck_token_blacklist_token_type")).isTrue();

        assertThat(indexExists("idx_email_verification_pending_lookup")).isTrue();
        assertThat(indexExists("idx_email_verification_email_created_at")).isTrue();
        assertThat(indexExists("idx_email_verification_expires_at")).isTrue();
        assertThat(indexExists("idx_token_blacklist_expires_at")).isTrue();

        assertThat(indexExists("idx_users_email")).isFalse();
        assertThat(indexExists("idx_users_username")).isFalse();
        assertThat(indexExists("idx_web3_nonces_wallet_address")).isFalse();
        assertThat(indexExists("idx_jti")).isFalse();
        assertThat(indexExists("idx_token_blacklist_jti")).isFalse();
        assertThat(indexExists("idx_expires_at")).isFalse();

        String emailCodeId = UUID.randomUUID().toString();
        String tokenBlacklistId = UUID.randomUUID().toString();
        String nonceId = UUID.randomUUID().toString();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO email_verification_codes (
                        id, created_at, email, expires_at, purpose,
                        updated_at, verification_code
                    ) VALUES (?, CURRENT_TIMESTAMP, ?,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                        'REGISTRATION', CURRENT_TIMESTAMP, '123456')
                    """,
                    emailCodeId,
                    "schema-default-" + emailCodeId + "@example.invalid"
            );
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT is_used::text || ':' || retry_count::text
                    FROM email_verification_codes
                    WHERE id = ?
                    """,
                    String.class,
                    emailCodeId
            )).isEqualTo("false:0");
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE email_verification_codes SET retry_count = -1 WHERE id = ?",
                    emailCodeId
            ))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "ck_email_verification_retry_count_nonnegative"
                    );

            jdbcTemplate.update(
                    """
                    INSERT INTO token_blacklist (
                        id, jti, token_type, expires_at
                    ) VALUES (?, ?, 'ACCESS', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                    """,
                    tokenBlacklistId,
                    "schema-jti-" + tokenBlacklistId
            );
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT blacklisted_at IS NOT NULL FROM token_blacklist WHERE id = ?",
                    Boolean.class,
                    tokenBlacklistId
            )).isTrue();
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE token_blacklist SET token_type = 'UNKNOWN' WHERE id = ?",
                    tokenBlacklistId
            ))
                    .hasRootCauseInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_token_blacklist_token_type");

            jdbcTemplate.update(
                    """
                    INSERT INTO web3_nonces (
                        id, wallet_address, nonce, message, expires_at
                    ) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                    """,
                    nonceId,
                    "0x" + nonceId.replace("-", "") + "00000000",
                    "schema-nonce-" + nonceId,
                    "schema-siwe-message-" + nonceId
            );
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT created_at IS NOT NULL FROM web3_nonces WHERE id = ?",
                    Boolean.class,
                    nonceId
            )).isTrue();
        } finally {
            jdbcTemplate.update("DELETE FROM web3_nonces WHERE id = ?", nonceId);
            jdbcTemplate.update("DELETE FROM token_blacklist WHERE id = ?", tokenBlacklistId);
            jdbcTemplate.update(
                    "DELETE FROM email_verification_codes WHERE id = ?",
                    emailCodeId
            );
        }
    }

    @Test
    void flywayCleanIsDisabled() {
        assertThatThrownBy(flyway::clean)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("cleanDisabled");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void flywayManagedSpringSessionTablesSupportRepositoryRoundTrip() {
        Session session = (Session) sessionRepository.createSession();
        session.setAttribute("integration-key", "integration-value");
        sessionRepository.save(session);

        Session restored = (Session) sessionRepository.findById(session.getId());
        assertThat(restored).isNotNull();
        assertThat((String) restored.getAttribute("integration-key"))
                .isEqualTo("integration-value");

        sessionRepository.deleteById(session.getId());
        assertThat(sessionRepository.findById(session.getId())).isNull();
    }

    private String columnDescriptor(String tableName, String columnName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT data_type || ':' || is_nullable || ':' || COALESCE(column_default, '')
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """,
                String.class,
                tableName,
                columnName
        );
    }

    private boolean constraintExists(String constraintName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint
                    WHERE connamespace = 'public'::regnamespace
                      AND conname = ?
                )
                """,
                Boolean.class,
                constraintName
        ));
    }

    private boolean indexExists(String indexName) {
        return jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.' || ?) IS NOT NULL",
                Boolean.class,
                indexName
        );
    }
}
