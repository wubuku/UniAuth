package org.dddml.uniauth.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmailVerificationPropertiesTest {

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                ConfigurationPropertiesAutoConfiguration.class
            ))
            .withUserConfiguration(EmailVerificationProperties.class);

    @Test
    void bindsValidatedVerificationSettingsInASpringContext() {
        contextRunner
            .withPropertyValues(
                "app.email.verification.code-length=6",
                "app.email.verification.expiry-minutes=15",
                "app.email.verification.max-send-per-day=25",
                "app.email.verification.max-retry-attempts=4",
                "app.email.verification.resend-cooldown-seconds=30"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                EmailVerificationProperties properties =
                    context.getBean(EmailVerificationProperties.class);
                assertThat(properties.getCodeLength()).isEqualTo(6);
                assertThat(properties.getExpiryMinutes()).isEqualTo(15);
                assertThat(properties.getMaxSendPerDay()).isEqualTo(25);
                assertThat(properties.getMaxRetryAttempts()).isEqualTo(4);
                assertThat(properties.getResendCooldownSeconds()).isEqualTo(30);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "app.email.verification.code-length=5",
        "app.email.verification.code-length=7",
        "app.email.verification.expiry-minutes=0",
        "app.email.verification.expiry-minutes=10081",
        "app.email.verification.max-send-per-day=0",
        "app.email.verification.max-retry-attempts=0",
        "app.email.verification.resend-cooldown-seconds=-1",
        "app.email.verification.resend-cooldown-seconds=86401"
    })
    void rejectsUnsafeVerificationSettingsAtContextStartup(String property) {
        contextRunner
            .withPropertyValues(property)
            .run(context -> assertThat(context).hasFailed());
    }
}
