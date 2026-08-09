package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HttpBoundaryPropertiesValidationTest {

    private final ApplicationContextRunner corsContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(CorsConfig.class)
                    .withPropertyValues(
                            "app.cors.allowed-origins=https://frontend.example.test",
                            "app.cors.allowed-methods=GET,POST,OPTIONS",
                            "app.cors.allowed-headers=authorization,content-type",
                            "app.cors.exposed-headers=authorization",
                            "app.cors.allow-credentials=true",
                            "app.cors.max-age=3600"
                    );

    private final ApplicationContextRunner frontendContextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(FrontendPropertiesConfiguration.class)
                    .withPropertyValues(
                            "app.frontend.type=react",
                            "app.frontend.url=https://frontend.example.test"
                    );

    @Test
    void validCorsConfigurationCreatesTheSingleSecuritySource() {
        corsContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CorsProperties.class);
            assertThat(context).hasBean("corsConfigurationSource");
        });
    }

    @Test
    void credentialedCorsRejectsWildcardOrigin() {
        corsContextRunner
                .withPropertyValues("app.cors.allowed-origins=*")
                .run(context -> assertValidationFailure(context, "wildcard origin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://frontend.example.test/path",
            "https://user@frontend.example.test",
            "https://frontend.example.test?tenant=one",
            "https://frontend.example.test#section",
            "https://frontend.example.test:",
            "https://frontend.example.test:0",
            "https://frontend.example.test:65536",
            "ftp://frontend.example.test"
    })
    void corsRejectsValuesThatAreNotExactHttpOrigins(String origin) {
        corsContextRunner
                .withPropertyValues("app.cors.allowed-origins=" + origin)
                .run(context -> assertValidationFailure(context, "exact HTTP(S) origins"));
    }

    @Test
    void corsRejectsAnEmptyOriginAllowlist() {
        corsContextRunner
                .withPropertyValues("app.cors.allowed-origins=")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "//evil.example",
            "javascript:alert(1)",
            "https://user@frontend.example.test",
            "https://frontend.example.test?tenant=one",
            "https://frontend.example.test#section",
            "https://frontend.example.test:",
            "https://frontend.example.test:0",
            "https://frontend.example.test:65536"
    })
    void frontendRejectsUnsafeDefaultUrls(String frontendUrl) {
        frontendContextRunner
                .withPropertyValues("app.frontend.url=" + frontendUrl)
                .run(context -> assertValidationFailure(context, "absolute HTTP(S) URL"));
    }

    @Test
    void frontendAcceptsAnAbsoluteUrlWithAContextPath() {
        frontendContextRunner
                .withPropertyValues("app.frontend.url=https://frontend.example.test/console")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void frontendRejectsAdditionalRedirectOriginsWithPaths() {
        frontendContextRunner
                .withPropertyValues(
                        "app.frontend.allowed-redirect-origins="
                                + "https://alternate.example.test/oauth/callback"
                )
                .run(context -> assertValidationFailure(context, "exact HTTP(S) origins"));
    }

    private void assertValidationFailure(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context,
            String message) {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(BindValidationException.class)
                .hasStackTraceContaining(message);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FrontendProperties.class)
    static class FrontendPropertiesConfiguration {
    }
}
