package org.dddml.uniauth.config;

import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DemoDataConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DemoDataConfiguration.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(UserLoginMethodRepository.class, () -> mock(UserLoginMethodRepository.class))
            .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
            .withBean(DataSource.class, () -> mock(DataSource.class));

    @Test
    void noProfileDoesNotCreateInitializer() {
        contextRunner
                .withPropertyValues(
                        "app.demo-data.enabled=true",
                        "app.demo-data.disposable=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataInitializer.class));
    }

    @Test
    void supportedProfilesKeepInitializerDisabledByDefault() {
        assertInitializerAbsentForProfile("dev");
        assertInitializerAbsentForProfile("test");
    }

    @Test
    void productionNeverCreatesInitializer() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues(
                        "app.demo-data.enabled=true",
                        "app.demo-data.disposable=true"
                )
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataInitializer.class));
    }

    @Test
    void explicitDevOptInCreatesInitializer() {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues(
                        "app.demo-data.enabled=true",
                        "app.demo-data.disposable=true"
                )
                .run(context -> assertThat(context).hasSingleBean(DemoDataInitializer.class));
    }

    private void assertInitializerAbsentForProfile(String profile) {
        contextRunner
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> assertThat(context).doesNotHaveBean(DemoDataInitializer.class));
    }
}
