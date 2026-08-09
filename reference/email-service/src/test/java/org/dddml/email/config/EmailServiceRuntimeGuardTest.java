package org.dddml.email.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailServiceRuntimeGuardTest {

    @Test
    void acceptsDedicatedLoopbackTestDatabase() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            ""
        );

        assertThatCode(guard::validateRuntime).doesNotThrowAnyException();
    }

    @Test
    void rejectsH2EvenForTheTestProfile() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:h2:mem:email_service_test",
            "127.0.0.1",
            ""
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PostgreSQL datasource URL");
    }

    @Test
    void rejectsFlywaySchemaMutationOverrides() {
        Map<String, String> unsafeOverrides = Map.of(
            "spring.flyway.enabled", "false",
            "spring.flyway.fail-on-missing-locations", "false",
            "spring.flyway.baseline-on-migrate", "true",
            "spring.flyway.clean-disabled", "false",
            "spring.flyway.validate-on-migrate", "false",
            "spring.flyway.out-of-order", "true",
            "spring.flyway.group", "false"
        );

        unsafeOverrides.forEach((property, value) ->
            assertSchemaPropertyRejected(
                property,
                value,
                property.replace('.', '_').replace('-', '_').toUpperCase()
            )
        );
    }

    @Test
    void rejectsFlywayOwnershipAndSchemaGenerationOverrides() {
        Map<String, String> unsafeOverrides = Map.of(
            "spring.flyway.locations", "classpath:db/migration/other",
            "spring.flyway.table", "flyway_schema_history",
            "spring.flyway.default-schema", "email",
            "spring.flyway.schemas", "email",
            "spring.flyway.baseline-version", "1",
            "spring.flyway.validate-migration-naming", "false",
            "spring.sql.init.mode", "always",
            "spring.jpa.hibernate.ddl-auto", "create-drop"
        );

        unsafeOverrides.forEach((property, value) ->
            assertSchemaPropertyRejected(
                property,
                value,
                property.replace('.', '_').replace('-', '_').toUpperCase()
            )
        );
    }

    @Test
    void rejectsSharedDatabaseBeforeFlywayCanRun() {
        EmailServiceRuntimeGuard guard = guard(
            "dev",
            "jdbc:postgresql://127.0.0.1:5432/uniauth_test",
            "127.0.0.1",
            ""
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared-uniauth");
    }

    @Test
    void acceptsExplicitSharedUniAuthDatabaseLayout() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/uniauth_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "app.email.database-layout",
                "shared-uniauth"
            )
        );

        assertThatCode(guard::validateRuntime).doesNotThrowAnyException();
    }

    @Test
    void rejectsReservedDatabaseEvenInSharedLayout() {
        EmailServiceRuntimeGuard guard = guard(
            "dev",
            "jdbc:postgresql://127.0.0.1:5432/blacksheep_email_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "app.email.database-layout",
                "shared-uniauth"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared or reserved");
    }

    @Test
    void rejectsUnknownDatabaseLayout() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "app.email.database-layout",
                "automatic"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL_DATABASE_LAYOUT");
    }

    @Test
    void rejectsNonLoopbackExposureWithoutCredential() {
        EmailServiceRuntimeGuard guard = guard(
            "prod",
            "jdbc:postgresql://127.0.0.1:5432/email_service_prod",
            "0.0.0.0",
            ""
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL_SERVICE_API_KEY");
    }

    @Test
    void rejectsImplicitOrCompositeProfiles() {
        EmailSecurityProperties security = new EmailSecurityProperties();
        MailProperties mail = new MailProperties();
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl("jdbc:postgresql://127.0.0.1:5432/email_service_test");
        MockEnvironment environment = environment("dev", "test");
        EmailServiceRuntimeGuard guard = new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            "127.0.0.1"
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Exactly one");
    }

    @Test
    void rejectsSmtpAuthenticationWithoutCredentials() {
        EmailSecurityProperties security = new EmailSecurityProperties();
        MailProperties mail = new MailProperties();
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl("jdbc:postgresql://127.0.0.1:5432/email_service_test");
        MockEnvironment environment = environment("test");
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "true");
        EmailServiceRuntimeGuard guard = new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            "127.0.0.1"
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP username");
    }

    @Test
    void rejectsProductionQueueWithoutRecoveryProcessing() {
        EmailSecurityProperties security = new EmailSecurityProperties();
        security.setApiKey("test-key");
        MailProperties mail = new MailProperties();
        mail.getRecovery().setEnabled(false);
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl("jdbc:postgresql://127.0.0.1:5432/email_service_prod");
        MockEnvironment environment = environment("prod");
        EmailServiceRuntimeGuard guard = new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            "0.0.0.0"
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recovery processing");
    }

    @Test
    void rejectsRecoveryScanIntervalsOutsideTheSupportedRange() {
        EmailSecurityProperties security = new EmailSecurityProperties();
        MailProperties mail = new MailProperties();
        mail.getRecovery().setScanIntervalMinutes(10081);
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl("jdbc:postgresql://127.0.0.1:5432/email_service_test");
        MockEnvironment environment = environment("test");
        EmailServiceRuntimeGuard guard = new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            "127.0.0.1"
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES");
    }

    @Test
    void rejectsRecoveryWindowShorterThanTheSmtpTimeoutBudget() {
        EmailSecurityProperties security = new EmailSecurityProperties();
        MailProperties mail = new MailProperties();
        mail.getRecovery().setStuckTimeoutMinutes(1);
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl("jdbc:postgresql://127.0.0.1:5432/email_service_test");
        MockEnvironment environment = environment("test");
        environment.setProperty(
            "spring.mail.properties.mail.smtp.connectiontimeout",
            "30000"
        );
        environment.setProperty("spring.mail.properties.mail.smtp.timeout", "30000");
        environment.setProperty(
            "spring.mail.properties.mail.smtp.writetimeout",
            "30000"
        );
        EmailServiceRuntimeGuard guard = new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            "127.0.0.1"
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("combined SMTP timeout budget");
    }

    @Test
    void rejectsServiceCredentialContainingHeaderDelimiters() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "first\nsecond"
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without CR or LF");
    }

    @Test
    void rejectsOversizedServiceCredential() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "x".repeat(1025)
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("1024");
    }

    @Test
    void rejectsMissingSmtpHost() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty("spring.mail.host", "")
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_HOST");
    }

    @Test
    void rejectsSmtpHostWithUriSyntax() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "spring.mail.host",
                "smtp://mail.example.test"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_HOST");
    }

    @Test
    void rejectsSmtpHostWithWhitespace() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "spring.mail.host",
                "mail host.example.test"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_HOST");
    }

    @Test
    void rejectsOversizedSmtpHost() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "spring.mail.host",
                "a".repeat(256)
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_HOST");
    }

    @Test
    void acceptsIpv6SmtpHostToken() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty("spring.mail.host", "::1")
        );

        assertThatCode(guard::validateRuntime).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonNumericSmtpPort() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "spring.mail.port",
                "not-a-port"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_PORT must be an integer from 1 to 65535");
    }

    @Test
    void rejectsOutOfRangeSmtpPort() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty("spring.mail.port", "65536")
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMTP_PORT must be an integer from 1 to 65535");
    }

    @Test
    void rejectsStartTlsRequirementWhenStartTlsIsDisabled() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> {
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.enable",
                    "false"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.required",
                    "true"
                );
            }
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "SMTP_STARTTLS_REQUIRED=true requires SMTP_STARTTLS_ENABLE=true"
            );
    }

    @Test
    void rejectsNonCanonicalSmtpBooleanValues() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
                "TRUE"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "SMTP_SSL_CHECK_SERVER_IDENTITY must be exactly true or false"
            );
    }

    @Test
    void rejectsImplicitSslTogetherWithStartTls() {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> {
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.enable",
                    "true"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.ssl.enable",
                    "true"
                );
            }
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "SMTP_SSL_ENABLE=true cannot be combined with SMTP_STARTTLS_ENABLE=true"
            );
    }

    @Test
    void rejectsProductionWithoutSmtpTransportEncryption() {
        EmailServiceRuntimeGuard guard = guard(
            "prod",
            "jdbc:postgresql://127.0.0.1:5432/email_service_prod",
            "127.0.0.1",
            "",
            environment -> {
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.enable",
                    "false"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.required",
                    "false"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.ssl.enable",
                    "false"
                );
            }
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Production SMTP requires forced STARTTLS or implicit SSL"
            );
    }

    @Test
    void rejectsProductionWithOptionalStartTls() {
        EmailServiceRuntimeGuard guard = guard(
            "prod",
            "jdbc:postgresql://127.0.0.1:5432/email_service_prod",
            "127.0.0.1",
            "",
            environment -> {
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.enable",
                    "true"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.required",
                    "false"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.ssl.enable",
                    "false"
                );
            }
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Production SMTP requires forced STARTTLS or implicit SSL"
            );
    }

    @Test
    void rejectsProductionWithoutSmtpServerIdentityVerification() {
        EmailServiceRuntimeGuard guard = guard(
            "prod",
            "jdbc:postgresql://127.0.0.1:5432/email_service_prod",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(
                "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
                "false"
            )
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(
                "Production SMTP requires server identity verification"
            );
    }

    @Test
    void acceptsProductionImplicitSslWithServerIdentityVerification() {
        EmailServiceRuntimeGuard guard = guard(
            "prod",
            "jdbc:postgresql://127.0.0.1:5432/email_service_prod",
            "127.0.0.1",
            "",
            environment -> {
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.enable",
                    "false"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.starttls.required",
                    "false"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.ssl.enable",
                    "true"
                );
                environment.setProperty(
                    "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
                    "true"
                );
            }
        );

        assertThatCode(guard::validateRuntime).doesNotThrowAnyException();
    }

    private EmailServiceRuntimeGuard guard(
            String profile,
            String jdbcUrl,
            String bindAddress,
            String apiKey) {
        return guard(profile, jdbcUrl, bindAddress, apiKey, environment -> {
        });
    }

    private EmailServiceRuntimeGuard guard(
            String profile,
            String jdbcUrl,
            String bindAddress,
            String apiKey,
            Consumer<MockEnvironment> environmentCustomizer) {
        EmailSecurityProperties security = new EmailSecurityProperties();
        security.setApiKey(apiKey);
        MailProperties mail = new MailProperties();
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl(jdbcUrl);
        MockEnvironment environment = environment(profile);
        environmentCustomizer.accept(environment);
        return new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            bindAddress
        );
    }

    private MockEnvironment environment(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty("spring.flyway.enabled", "true");
        environment.setProperty("spring.flyway.fail-on-missing-locations", "true");
        environment.setProperty("spring.flyway.baseline-on-migrate", "false");
        environment.setProperty("spring.flyway.baseline-version", "0");
        environment.setProperty("spring.flyway.clean-disabled", "true");
        environment.setProperty("spring.flyway.validate-migration-naming", "true");
        environment.setProperty("spring.flyway.validate-on-migrate", "true");
        environment.setProperty("spring.flyway.out-of-order", "false");
        environment.setProperty("spring.flyway.group", "true");
        environment.setProperty(
            "spring.flyway.locations",
            "classpath:db/migration/postgresql"
        );
        environment.setProperty(
            "spring.flyway.table",
            "email_service_flyway_schema_history"
        );
        environment.setProperty("spring.flyway.default-schema", "public");
        environment.setProperty("spring.flyway.schemas", "public");
        environment.setProperty("spring.sql.init.mode", "never");
        environment.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        environment.setProperty("spring.mail.host", "127.0.0.1");
        environment.setProperty("spring.mail.port", "2525");
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "false");
        return environment;
    }

    private void assertSchemaPropertyRejected(
            String property,
            String value,
            String environmentVariableName) {
        EmailServiceRuntimeGuard guard = guard(
            "test",
            "jdbc:postgresql://127.0.0.1:5432/email_service_test",
            "127.0.0.1",
            "",
            environment -> environment.setProperty(property, value)
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(environmentVariableName);
    }
}
