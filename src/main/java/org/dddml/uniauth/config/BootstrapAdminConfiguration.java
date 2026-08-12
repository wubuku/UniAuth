package org.dddml.uniauth.config;

import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.BootstrapAdminInitializer;
import org.dddml.uniauth.service.CanonicalEmailService;
import org.dddml.uniauth.service.PasswordPolicyService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BootstrapAdminProperties.class)
public class BootstrapAdminConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "app.bootstrap-admin",
            name = "enabled",
            havingValue = "true"
    )
    BootstrapAdminInitializer bootstrapAdminInitializer(
            UserRepository userRepository,
            UserLoginMethodRepository loginMethodRepository,
            PasswordEncoder passwordEncoder,
            PasswordPolicyService passwordPolicyService,
            CanonicalEmailService canonicalEmailService,
            BootstrapAdminProperties properties) {
        return new BootstrapAdminInitializer(
                userRepository,
                loginMethodRepository,
                passwordEncoder,
                passwordPolicyService,
                canonicalEmailService,
                properties
        );
    }
}
