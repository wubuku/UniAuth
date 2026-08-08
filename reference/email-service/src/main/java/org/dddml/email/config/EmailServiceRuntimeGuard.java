package org.dddml.email.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

@Configuration(proxyBeanMethods = false)
public class EmailServiceRuntimeGuard {

    private final EmailSecurityProperties securityProperties;
    private final MailProperties mailProperties;
    private final Environment environment;
    private final DataSourceProperties dataSourceProperties;
    private final String bindAddress;

    public EmailServiceRuntimeGuard(
            EmailSecurityProperties securityProperties,
            MailProperties mailProperties,
            Environment environment,
            DataSourceProperties dataSourceProperties,
            @Value("${server.address:127.0.0.1}") String bindAddress) {
        this.securityProperties = securityProperties;
        this.mailProperties = mailProperties;
        this.environment = environment;
        this.dataSourceProperties = dataSourceProperties;
        this.bindAddress = bindAddress;
    }

    @PostConstruct
    void validateRuntime() {
        String profile = validateProfile();
        validateDatabaseTarget();
        validateSmtpAuthentication();
        validateSmtpTransport(profile);
        validateSmtpTimeouts();
        validateRecoveryConfiguration();
        validateDeliveryConfiguration(profile);
        validateServiceCredential();
        if (!isLoopback(bindAddress) && !StringUtils.hasText(securityProperties.getApiKey())) {
            throw new IllegalStateException(
                "EMAIL_SERVICE_API_KEY is required when the email service is not bound to loopback"
            );
        }
    }

    @Bean
    FlywayMigrationStrategy guardedFlywayMigrationStrategy() {
        return flyway -> {
            validateDatabaseTarget();
            flyway.migrate();
        };
    }

    private String validateProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (activeProfiles.length != 1
                || !Arrays.asList("dev", "test", "prod").contains(activeProfiles[0])) {
            throw new IllegalStateException(
                "Exactly one of dev, test, or prod must be active for the email service"
            );
        }
        return activeProfiles[0];
    }

    private void validateDatabaseTarget() {
        String profile = validateProfile();
        String jdbcUrl = dataSourceProperties.getUrl();
        String databaseName = databaseName(jdbcUrl);
        String normalized = databaseName.toLowerCase(Locale.ROOT);

        if (!normalized.contains("email") && !normalized.contains("mail")) {
            throw new IllegalStateException(
                "Email service database name must contain email or mail"
            );
        }
        if (isReservedDatabase(normalized)) {
            throw new IllegalStateException(
                "Refusing a shared or reserved PostgreSQL database"
            );
        }
        if ("dev".equals(profile)
                && !containsAny(normalized, "dev", "test", "demo", "local")) {
            throw new IllegalStateException(
                "Email service dev profile requires a dev/test/demo/local database"
            );
        }
        if ("test".equals(profile) && !containsAny(normalized, "test", "demo")) {
            throw new IllegalStateException(
                "Email service test profile requires a disposable test/demo database"
            );
        }
        if (!"test".equals(profile) && !jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                "Email service dev/prod profiles require PostgreSQL"
            );
        }
    }

    private String databaseName(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl)) {
            throw new IllegalStateException("Email service datasource URL is required");
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            try {
                String path = URI.create(jdbcUrl.substring("jdbc:".length())).getPath();
                if (!StringUtils.hasText(path) || "/".equals(path)) {
                    throw new IllegalStateException(
                        "Email service PostgreSQL database name is missing"
                    );
                }
                return path.substring(1);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "Invalid email service PostgreSQL datasource URL",
                    exception
                );
            }
        }
        if (jdbcUrl.startsWith("jdbc:h2:mem:")) {
            return jdbcUrl.substring("jdbc:h2:mem:".length()).split("[;?]", 2)[0];
        }
        throw new IllegalStateException("Unsupported email service datasource URL");
    }

    private void validateSmtpAuthentication() {
        boolean smtpAuth = strictBooleanProperty(
            "spring.mail.properties.mail.smtp.auth",
            true,
            "SMTP_AUTH"
        );
        if (!smtpAuth) {
            return;
        }
        if (!StringUtils.hasText(environment.getProperty("spring.mail.username"))) {
            throw new IllegalStateException(
                "SMTP username is required when SMTP authentication is enabled"
            );
        }
        if (!StringUtils.hasText(environment.getProperty("spring.mail.password"))) {
            throw new IllegalStateException(
                "SMTP password is required when SMTP authentication is enabled"
            );
        }
    }

    private void validateDeliveryConfiguration(String profile) {
        if ("prod".equals(profile)
                && mailProperties.isEnabled()
                && mailProperties.getQueue().isEnabled()
                && !mailProperties.getRecovery().isEnabled()) {
            throw new IllegalStateException(
                "Production email delivery requires recovery processing"
            );
        }
    }

    private void validateSmtpTransport(String profile) {
        boolean startTlsEnabled = strictBooleanProperty(
            "spring.mail.properties.mail.smtp.starttls.enable",
            true,
            "SMTP_STARTTLS_ENABLE"
        );
        boolean startTlsRequired = strictBooleanProperty(
            "spring.mail.properties.mail.smtp.starttls.required",
            true,
            "SMTP_STARTTLS_REQUIRED"
        );
        boolean implicitSslEnabled = strictBooleanProperty(
            "spring.mail.properties.mail.smtp.ssl.enable",
            false,
            "SMTP_SSL_ENABLE"
        );
        boolean serverIdentityVerification = strictBooleanProperty(
            "spring.mail.properties.mail.smtp.ssl.checkserveridentity",
            true,
            "SMTP_SSL_CHECK_SERVER_IDENTITY"
        );

        if (startTlsRequired && !startTlsEnabled) {
            throw new IllegalStateException(
                "SMTP_STARTTLS_REQUIRED=true requires SMTP_STARTTLS_ENABLE=true"
            );
        }
        if (implicitSslEnabled && startTlsEnabled) {
            throw new IllegalStateException(
                "SMTP_SSL_ENABLE=true cannot be combined with SMTP_STARTTLS_ENABLE=true"
            );
        }
        if (!"prod".equals(profile)) {
            return;
        }
        if (!implicitSslEnabled && !(startTlsEnabled && startTlsRequired)) {
            throw new IllegalStateException(
                "Production SMTP requires forced STARTTLS or implicit SSL"
            );
        }
        if (!serverIdentityVerification) {
            throw new IllegalStateException(
                "Production SMTP requires server identity verification"
            );
        }
    }

    private void validateServiceCredential() {
        String apiKey = securityProperties.getApiKey();
        if (apiKey == null) {
            return;
        }
        if (apiKey.length() > 1024 || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new IllegalStateException(
                "EMAIL_SERVICE_API_KEY must be at most 1024 characters without CR or LF"
            );
        }
    }

    private boolean strictBooleanProperty(
            String propertyName,
            boolean defaultValue,
            String environmentVariableName) {
        String value = environment.getProperty(propertyName);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalStateException(
            environmentVariableName + " must be exactly true or false"
        );
    }

    private void validateRecoveryConfiguration() {
        int scanIntervalMinutes =
            mailProperties.getRecovery().getScanIntervalMinutes();
        if (scanIntervalMinutes < 1 || scanIntervalMinutes > 10080) {
            throw new IllegalStateException(
                "EMAIL_RECOVERY_SCAN_INTERVAL_MINUTES must be between 1 and 10080"
            );
        }
    }

    private void validateSmtpTimeouts() {
        long connectionTimeout = timeoutMillis(
            "spring.mail.properties.mail.smtp.connectiontimeout"
        );
        long readTimeout = timeoutMillis(
            "spring.mail.properties.mail.smtp.timeout"
        );
        long writeTimeout = timeoutMillis(
            "spring.mail.properties.mail.smtp.writetimeout"
        );

        if (!mailProperties.getRecovery().isEnabled()) {
            return;
        }

        long deliveryBudget;
        try {
            deliveryBudget = Math.addExact(
                Math.addExact(connectionTimeout, readTimeout),
                writeTimeout
            );
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("SMTP timeout budget is too large", exception);
        }

        long stuckTimeout = Duration.ofMinutes(
            mailProperties.getRecovery().getStuckTimeoutMinutes()
        ).toMillis();
        if (deliveryBudget >= stuckTimeout) {
            throw new IllegalStateException(
                "EMAIL_STUCK_TIMEOUT_MINUTES must exceed the combined SMTP timeout budget"
            );
        }
    }

    private long timeoutMillis(String propertyName) {
        long timeout = environment.getProperty(propertyName, Long.class, 10000L);
        if (timeout < 100 || timeout > 600000) {
            throw new IllegalStateException(
                propertyName + " must be between 100 and 600000 milliseconds"
            );
        }
        return timeout;
    }

    private boolean isReservedDatabase(String databaseName) {
        return databaseName.equals("blacksheep")
            || databaseName.startsWith("blacksheep_")
            || databaseName.startsWith("blacksheep-")
            || databaseName.equals("postgres")
            || databaseName.equals("template0")
            || databaseName.equals("template1")
            || databaseName.equals("uniauth")
            || databaseName.equals("uniauth_dev")
            || databaseName.equals("uniauth_test");
    }

    private boolean containsAny(String value, String... fragments) {
        return Arrays.stream(fragments).anyMatch(value::contains);
    }

    private boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid email service bind address", exception);
        }
    }
}
