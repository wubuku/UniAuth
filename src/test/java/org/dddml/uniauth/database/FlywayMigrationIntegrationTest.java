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
    void freshDatabaseMigratesToVersionTwoAndHibernateValidates() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("2");
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
}
