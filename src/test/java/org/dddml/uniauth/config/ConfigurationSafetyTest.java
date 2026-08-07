package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationSafetyTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void baseConfigurationDoesNotActivateAProfile() throws IOException {
        assertThat(property("application.yml", "spring.profiles.active")).isNull();
    }

    @Test
    void testDatabaseRequiresExplicitConnectionSettings() throws IOException {
        assertThat(property("application-test.yml", "spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}");
        assertThat(property("application-test.yml", "spring.datasource.username"))
                .isEqualTo("${POSTGRES_USER}");
        assertThat(property("application-test.yml", "spring.datasource.password"))
                .isEqualTo("${POSTGRES_PASSWORD}");
    }

    @Test
    void developmentDatabaseRequiresExplicitPostgreSqlConnectionSettings() throws IOException {
        assertThat(property("application-dev.yml", "spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}");
        assertThat(property("application-dev.yml", "spring.datasource.username"))
                .isEqualTo("${POSTGRES_USER}");
        assertThat(property("application-dev.yml", "spring.datasource.password"))
                .isEqualTo("${POSTGRES_PASSWORD}");
    }

    @Test
    void supportedProfilesDelegateSchemaOwnershipToFlyway() throws IOException {
        assertThat(property("application.yml", "spring.flyway.enabled")).isEqualTo(true);
        assertThat(property("application.yml", "spring.flyway.locations"))
                .isEqualTo("classpath:db/migration/postgresql");
        assertThat(property("application.yml", "spring.flyway.table"))
                .isEqualTo("uniauth_flyway_schema_history");
        assertThat(property("application.yml", "spring.flyway.baseline-on-migrate"))
                .isEqualTo(false);
        assertThat(property("application.yml", "spring.flyway.clean-disabled"))
                .isEqualTo(true);

        for (String profile : List.of("dev", "test", "prod")) {
            assertThat(property("application-" + profile + ".yml", "spring.jpa.hibernate.ddl-auto"))
                    .isEqualTo("validate");
            assertThat(property("application-" + profile + ".yml", "spring.sql.init.mode"))
                    .isEqualTo("never");
            assertThat(property("application-" + profile + ".yml",
                    "spring.session.jdbc.initialize-schema")).isEqualTo("never");
        }
    }

    @Test
    void runtimeClasspathContainsOnlyTheApprovedPostgreSqlFlywayMigrations()
            throws IOException {
        for (String retiredResource : List.of(
                "schema-postgresql.sql",
                "schema-sqlite.sql",
                "data-postgresql.sql",
                "data-sqlite.sql",
                "db/migration/V1__Create_user_login_methods_table.sql",
                "db/migration/V8__Create_email_verification_codes_table.sql"
        )) {
            assertThat(getClass().getClassLoader().getResource(retiredResource))
                    .as(retiredResource)
                    .isNull();
        }

        Path migrationDirectory = Path.of(
                "src/main/resources/db/migration/postgresql"
        );
        try (var files = Files.list(migrationDirectory)) {
            assertThat(files
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList())
                    .containsExactly(
                            "V1__baseline_uniauth_auth_schema.sql",
                            "V2__harden_login_method_invariants.sql"
                    );
        }
    }

    @Test
    void oauthCredentialsHaveNoRunnableFallbacks() throws IOException {
        assertThat(property("application.yml",
                "spring.security.oauth2.client.registration.google.client-id"))
                .isEqualTo("${GOOGLE_CLIENT_ID}");
        assertThat(property("application.yml",
                "spring.security.oauth2.client.registration.google.client-secret"))
                .isEqualTo("${GOOGLE_CLIENT_SECRET}");
        assertThat(property("application.yml",
                "spring.security.oauth2.client.registration.github.client-id"))
                .isEqualTo("${GITHUB_CLIENT_ID}");
        assertThat(property("application.yml",
                "spring.security.oauth2.client.registration.github.client-secret"))
                .isEqualTo("${GITHUB_CLIENT_SECRET}");
        assertThat(property("application.yml",
                "spring.security.oauth2.client.registration.x.client-id"))
                .isEqualTo("${TWITTER_CLIENT_ID}");
        assertThat(property("application.yml",
                "spring.security.oauth2.client.registration.x.client-secret"))
                .isEqualTo("${TWITTER_CLIENT_SECRET}");
    }

    @Test
    void developmentAndTestProfilesDoNotEnableSensitiveSqlOrFrameworkDebugLogs() throws IOException {
        assertThat(property("application-dev.yml", "spring.jpa.show-sql")).isEqualTo(false);
        assertThat(property("application-test.yml", "spring.jpa.show-sql")).isEqualTo(false);
        assertThat(property("application-dev.yml", "logging.level.org.springframework.security"))
                .isEqualTo("INFO");
        assertThat(property("application-dev.yml", "logging.level.org.springframework.web"))
                .isEqualTo("INFO");
        assertThat(property("application-test.yml", "logging.level.org.hibernate.SQL"))
                .isEqualTo("INFO");
    }

    @Test
    void rsaKeyUsesAnIgnoredConfigurablePath() throws IOException {
        assertThat(property("application.yml", "jwt.rsa.key-file"))
                .isEqualTo("${JWT_RSA_KEY_FILE:.local/uniauth/rsa-keys.ser}");
    }

    @Test
    void runtimeGuardKeepsTestProfileOffDevelopmentDatabases() throws IOException {
        assertThat(runRuntimeGuard("test", "blacksheep_dev")).isNotZero();
        assertThat(runRuntimeGuard("test", "uniauth_http_e2e_test")).isZero();
        assertThat(runRuntimeGuard("dev", "blacksheep_dev")).isZero();
    }

    private int runRuntimeGuard(String profile, String databaseName) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "bash",
                "-c",
                "source scripts/runtime-guard.sh && uniauth_prepare_runtime ."
        );
        Map<String, String> environment = processBuilder.environment();
        environment.put("SPRING_PROFILES_ACTIVE", profile);
        environment.put("POSTGRES_HOST", "127.0.0.1");
        environment.put("POSTGRES_PORT", "5432");
        environment.put("POSTGRES_DATABASE", databaseName);
        environment.put("POSTGRES_USER", "uniauth");
        environment.put("POSTGRES_PASSWORD", "test-only");
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while testing runtime guard", e);
        }
    }

    private Object property(String resourceName, String propertyName) throws IOException {
        List<PropertySource<?>> sources = loader.load(
                resourceName,
                new ClassPathResource(resourceName)
        );
        return sources.stream()
                .map(source -> source.getProperty(propertyName))
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }
}
