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

import java.util.List;

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
    void freshDatabaseMigratesToVersionOneAndHibernateValidates() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
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
