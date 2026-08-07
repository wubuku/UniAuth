package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

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
