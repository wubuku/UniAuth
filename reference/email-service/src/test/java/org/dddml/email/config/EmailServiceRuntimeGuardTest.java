package org.dddml.email.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

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
    void rejectsSharedDatabaseBeforeFlywayCanRun() {
        EmailServiceRuntimeGuard guard = guard(
            "dev",
            "jdbc:postgresql://127.0.0.1:5432/blacksheep_email_test",
            "127.0.0.1",
            ""
        );

        assertThatThrownBy(guard::validateRuntime)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("shared or reserved");
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
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "false");
        environment.setActiveProfiles("dev", "test");
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
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
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
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "false");
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
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "false");
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
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "false");
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

    private EmailServiceRuntimeGuard guard(
            String profile,
            String jdbcUrl,
            String bindAddress,
            String apiKey) {
        EmailSecurityProperties security = new EmailSecurityProperties();
        security.setApiKey(apiKey);
        MailProperties mail = new MailProperties();
        DataSourceProperties datasource = new DataSourceProperties();
        datasource.setUrl(jdbcUrl);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("spring.mail.properties.mail.smtp.auth", "false");
        return new EmailServiceRuntimeGuard(
            security,
            mail,
            environment,
            datasource,
            bindAddress
        );
    }
}
