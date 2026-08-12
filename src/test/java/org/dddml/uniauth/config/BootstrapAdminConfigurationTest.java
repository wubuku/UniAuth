package org.dddml.uniauth.config;

import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.BootstrapAdminInitializer;
import org.dddml.uniauth.service.CanonicalEmailService;
import org.dddml.uniauth.service.PasswordPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BootstrapAdminConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(BootstrapAdminConfiguration.class)
                    .withBean(UserRepository.class, () -> mock(UserRepository.class))
                    .withBean(UserLoginMethodRepository.class,
                            () -> mock(UserLoginMethodRepository.class))
                    .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                    .withBean(PasswordPolicyService.class,
                            () -> mock(PasswordPolicyService.class))
                    .withBean(CanonicalEmailService.class,
                            CanonicalEmailService::new);

    @Test
    void bootstrapAdminIsDisabledByDefault() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(BootstrapAdminInitializer.class));
    }

    @Test
    void explicitEnablementCreatesBootstrapInitializer() {
        contextRunner
                .withPropertyValues("app.bootstrap-admin.enabled=true")
                .run(context ->
                        assertThat(context)
                                .hasSingleBean(BootstrapAdminInitializer.class));
    }
}
