package org.dddml.email.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class EmailServiceRuntimeGuardApplicationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RuntimeGuardConfiguration.class)
        .withPropertyValues(
            "spring.profiles.active=test",
            "server.address=127.0.0.1",
            "spring.datasource.url=jdbc:h2:mem:email_service_test",
            "app.mail.from-email=no-reply@example.test",
            "app.mail.from-name=Email Service Test"
        );

    @Test
    void testProfileRejectsH2DuringApplicationContextStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("PostgreSQL datasource URL");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({
        DataSourceProperties.class,
        EmailSecurityProperties.class,
        MailProperties.class
    })
    @Import(EmailServiceRuntimeGuard.class)
    static class RuntimeGuardConfiguration {
    }
}
