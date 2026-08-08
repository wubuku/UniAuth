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
        validateSchemaOwnershipConfiguration();
        validateSmtpEndpoint();
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
            EmailSharedSchemaFlywayBootstrap.migrate(
                flyway,
                "shared-uniauth".equals(databaseLayout())
            );
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
        String databaseLayout = databaseLayout();
        String jdbcUrl = dataSourceProperties.getUrl();
        String databaseName = databaseName(jdbcUrl);
        String normalized = databaseName.toLowerCase(Locale.ROOT);

        if (isAlwaysReservedDatabase(normalized)) {
            throw new IllegalStateException(
                "Refusing a shared or reserved PostgreSQL database"
            );
        }
        if ("dedicated".equals(databaseLayout)) {
            if (isUniAuthDatabase(normalized)) {
                throw new IllegalStateException(
                    "EMAIL_DATABASE_LAYOUT=shared-uniauth is required "
                        + "for a UniAuth database"
                );
            }
            if (!normalized.contains("email") && !normalized.contains("mail")) {
                throw new IllegalStateException(
                    "Email service database name must contain email or mail"
                );
            }
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
    }

    private String databaseLayout() {
        String layout = environment.getProperty(
            "app.email.database-layout",
            "dedicated"
        );
        if (!"dedicated".equals(layout) && !"shared-uniauth".equals(layout)) {
            throw new IllegalStateException(
                "EMAIL_DATABASE_LAYOUT must be exactly dedicated or shared-uniauth"
            );
        }
        return layout;
    }

    private String databaseName(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl)) {
            throw new IllegalStateException("Email service datasource URL is required");
        }
        if (!jdbcUrl.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException(
                "Email service requires a PostgreSQL datasource URL"
            );
        }
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

    private void validateSchemaOwnershipConfiguration() {
        requireStrictBooleanProperty(
            "spring.flyway.enabled",
            true,
            "SPRING_FLYWAY_ENABLED"
        );
        requireStrictBooleanProperty(
            "spring.flyway.fail-on-missing-locations",
            true,
            "SPRING_FLYWAY_FAIL_ON_MISSING_LOCATIONS"
        );
        requireStrictBooleanProperty(
            "spring.flyway.baseline-on-migrate",
            false,
            "SPRING_FLYWAY_BASELINE_ON_MIGRATE"
        );
        requireExactProperty(
            "spring.flyway.baseline-version",
            "0",
            "SPRING_FLYWAY_BASELINE_VERSION"
        );
        requireStrictBooleanProperty(
            "spring.flyway.clean-disabled",
            true,
            "SPRING_FLYWAY_CLEAN_DISABLED"
        );
        requireStrictBooleanProperty(
            "spring.flyway.validate-on-migrate",
            true,
            "SPRING_FLYWAY_VALIDATE_ON_MIGRATE"
        );
        requireStrictBooleanProperty(
            "spring.flyway.out-of-order",
            false,
            "SPRING_FLYWAY_OUT_OF_ORDER"
        );
        requireExactProperty(
            "spring.flyway.locations",
            "classpath:db/migration/postgresql",
            "SPRING_FLYWAY_LOCATIONS"
        );
        requireExactProperty(
            "spring.flyway.table",
            "email_service_flyway_schema_history",
            "SPRING_FLYWAY_TABLE"
        );
        requireExactProperty(
            "spring.flyway.default-schema",
            "public",
            "SPRING_FLYWAY_DEFAULT_SCHEMA"
        );
        requireExactProperty(
            "spring.flyway.schemas",
            "public",
            "SPRING_FLYWAY_SCHEMAS"
        );
        requireStrictBooleanProperty(
            "spring.flyway.validate-migration-naming",
            true,
            "SPRING_FLYWAY_VALIDATE_MIGRATION_NAMING"
        );
        requireExactProperty(
            "spring.sql.init.mode",
            "never",
            "SPRING_SQL_INIT_MODE"
        );
        requireExactProperty(
            "spring.jpa.hibernate.ddl-auto",
            "validate",
            "SPRING_JPA_HIBERNATE_DDL_AUTO"
        );
    }

    private void requireStrictBooleanProperty(
            String propertyName,
            boolean expected,
            String environmentVariableName) {
        String expectedValue = Boolean.toString(expected);
        String actualValue = environment.getProperty(propertyName);
        if (!expectedValue.equals(actualValue)) {
            throw new IllegalStateException(
                environmentVariableName + " must be exactly " + expectedValue
            );
        }
    }

    private void requireExactProperty(
            String propertyName,
            String expected,
            String environmentVariableName) {
        String actual = environment.getProperty(propertyName);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                environmentVariableName + " must be exactly " + expected
            );
        }
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

    private void validateSmtpEndpoint() {
        String host = environment.getProperty("spring.mail.host");
        if (!StringUtils.hasText(host)
                || host.length() > 255
                || host.codePoints().anyMatch(
                    character -> Character.isWhitespace(character)
                        || Character.isISOControl(character)
                )
                || host.indexOf('/') >= 0
                || host.indexOf('@') >= 0
                || host.indexOf('?') >= 0
                || host.indexOf('#') >= 0) {
            throw new IllegalStateException(
                "SMTP_HOST must be a host name or IP address without URI syntax or whitespace"
            );
        }

        strictIntegerProperty("spring.mail.port", "SMTP_PORT", 1, 65535);
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

    private int strictIntegerProperty(
            String propertyName,
            String environmentVariableName,
            int minimum,
            int maximum) {
        String value = environment.getProperty(propertyName);
        if (value == null
                || value.length() > 9
                || !value.matches("[0-9]+")) {
            throw new IllegalStateException(
                environmentVariableName + " must be an integer from "
                    + minimum + " to " + maximum
            );
        }

        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                environmentVariableName + " must be an integer from "
                    + minimum + " to " + maximum,
                exception
            );
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalStateException(
                environmentVariableName + " must be an integer from "
                    + minimum + " to " + maximum
            );
        }
        return parsed;
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

    private boolean isAlwaysReservedDatabase(String databaseName) {
        return databaseName.equals("blacksheep")
            || databaseName.startsWith("blacksheep_")
            || databaseName.startsWith("blacksheep-")
            || databaseName.equals("postgres")
            || databaseName.equals("template0")
            || databaseName.equals("template1");
    }

    private boolean isUniAuthDatabase(String databaseName) {
        return databaseName.equals("uniauth")
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
