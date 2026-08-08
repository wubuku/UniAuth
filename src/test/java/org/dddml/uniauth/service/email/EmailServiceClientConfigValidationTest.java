package org.dddml.uniauth.service.email;

import org.dddml.uniauth.config.EmailServiceClientConfig;
import org.dddml.uniauth.config.EmailServiceClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceClientConfigValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(
            RestTemplateAutoConfiguration.class,
            EmailServiceClientConfig.class
        )
        .withPropertyValues(
            "app.email.service.url=http://127.0.0.1:8095",
            "app.email.service.timeout=5000"
        );

    @Test
    void acceptsAnHttpsBaseUrlWithAContextPath() {
        contextRunner
            .withPropertyValues(
                "app.email.service.url=https://mail.example.test/email-service/"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("emailRestTemplate");
            });
    }

    @Test
    void clientPropertiesDoNotExposeTheServiceCredentialInObjectStrings() {
        EmailServiceClientProperties properties = new EmailServiceClientProperties();
        properties.setApiKey("client-secret-value");

        assertThat(properties.toString()).doesNotContain("client-secret-value");
    }

    @Test
    void rejectsAServiceUrlThatCannotBeUsedAsAnHttpBaseUrl() {
        contextRunner
            .withPropertyValues("app.email.service.url=ftp://user:secret@example.test/mail")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("absolute HTTP(S) URL");
            });
    }

    @Test
    void rejectsAServiceUrlWithUserInfo() {
        contextRunner
            .withPropertyValues(
                "app.email.service.url=https://user:secret@mail.example.test/service"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("without user info");
            });
    }

    @Test
    void rejectsAServiceUrlWithAQuery() {
        contextRunner
            .withPropertyValues(
                "app.email.service.url=https://mail.example.test/service?tenant=uniauth"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("query");
            });
    }

    @Test
    void rejectsAServiceUrlWithAFragment() {
        contextRunner
            .withPropertyValues(
                "app.email.service.url=https://mail.example.test/service#health"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("fragment");
            });
    }

    @Test
    void rejectsAnHttpBaseUrlWithoutAHost() {
        contextRunner
            .withPropertyValues("app.email.service.url=http://:8095")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("with a host");
            });
    }

    @Test
    void rejectsAnEmailServiceTimeoutBelowTheOperationalFloor() {
        contextRunner
            .withPropertyValues("app.email.service.timeout=99")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("timeout")
                    .hasStackTraceContaining("100");
            });
    }

    @Test
    void rejectsAServiceCredentialContainingHeaderDelimiters() {
        contextRunner
            .withSystemProperties("app.email.service.api-key=first\nsecond")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("apiKey");
            });
    }

    @Test
    void rejectsAnOversizedServiceCredential() {
        contextRunner
            .withPropertyValues("app.email.service.api-key=" + "x".repeat(1025))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("apiKey")
                    .hasStackTraceContaining("1024");
            });
    }

    @Test
    void rejectsAnEffectivelyUnboundedEmailServiceTimeout() {
        contextRunner
            .withPropertyValues("app.email.service.timeout=600001")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(BindValidationException.class)
                    .hasStackTraceContaining("timeout")
                    .hasStackTraceContaining("600000");
            });
    }
}
