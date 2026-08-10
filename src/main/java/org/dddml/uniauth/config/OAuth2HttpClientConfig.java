package org.dddml.uniauth.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties(OAuth2HttpClientProperties.class)
public class OAuth2HttpClientConfig {

    @Bean("oauth2RestTemplate")
    RestTemplate oauth2RestTemplate(
            RestTemplateBuilder builder,
            OAuth2HttpClientProperties properties) {
        return boundedBuilder(builder, properties).build();
    }

    @Bean("oauth2TokenRestTemplate")
    RestTemplate oauth2TokenRestTemplate(
            RestTemplateBuilder builder,
            OAuth2HttpClientProperties properties) {
        RestTemplate restTemplate = boundedBuilder(builder, properties)
                .build();
        restTemplate.setMessageConverters(List.of(
                new FormHttpMessageConverter(),
                new OAuth2AccessTokenResponseHttpMessageConverter()
        ));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        return restTemplate;
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>
            oauth2AuthorizationCodeTokenResponseClient(
                    @Qualifier("oauth2TokenRestTemplate")
                    RestTemplate restTemplate) {
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.create(restTemplate));
        return client;
    }

    private RestTemplateBuilder boundedBuilder(
            RestTemplateBuilder builder,
            OAuth2HttpClientProperties properties) {
        return builder
                .connectTimeout(Duration.ofMillis(
                        properties.getConnectTimeoutMs()
                ))
                .readTimeout(Duration.ofMillis(
                        properties.getReadTimeoutMs()
                ));
    }
}
