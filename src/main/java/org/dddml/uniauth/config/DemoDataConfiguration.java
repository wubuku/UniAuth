package org.dddml.uniauth.config;

import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile({"dev", "test"})
@EnableConfigurationProperties(DemoDataProperties.class)
public class DemoDataConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.demo-data", name = "enabled", havingValue = "true")
    DemoDataInitializer demoDataInitializer(
            UserRepository userRepository,
            UserLoginMethodRepository loginMethodRepository,
            PasswordEncoder passwordEncoder,
            DataSource dataSource,
            DemoDataProperties properties) {
        return new DemoDataInitializer(
                userRepository,
                loginMethodRepository,
                passwordEncoder,
                dataSource,
                properties
        );
    }
}
