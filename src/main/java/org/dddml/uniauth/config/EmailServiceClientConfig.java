package org.dddml.uniauth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(EmailServiceClientProperties.class)
public class EmailServiceClientConfig {

    @Bean("emailRestTemplate")
    RestTemplate emailRestTemplate(
            RestTemplateBuilder builder,
            EmailServiceClientProperties properties) {
        Duration timeout = Duration.ofMillis(properties.getTimeout());
        return builder
            .setConnectTimeout(timeout)
            .setReadTimeout(timeout)
            .build();
    }
}
