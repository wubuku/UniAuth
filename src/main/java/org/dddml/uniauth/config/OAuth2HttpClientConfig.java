package org.dddml.uniauth.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
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

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@Configuration
@EnableConfigurationProperties(OAuth2HttpClientProperties.class)
@Slf4j
public class OAuth2HttpClientConfig {

    @Bean("oauth2RestTemplate")
    RestTemplate oauth2RestTemplate(
            RestTemplateBuilder builder,
            OAuth2HttpClientProperties properties) {
        log.info(
                "OAuth2 HTTP client configured: proxy={}, connectTimeoutMs={}, readTimeoutMs={}",
                properties.proxyRouteDescription(),
                properties.getConnectTimeoutMs(),
                properties.getReadTimeoutMs()
        );
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
        var requestFactoryBuilder = ClientHttpRequestFactoryBuilder.jdk();
        URI proxyUri = properties.getProxyMode()
                == OAuth2HttpClientProperties.ProxyMode.DIRECT
                ? null
                : properties.proxyUri();
        ProxySelector proxySelector = proxySelector(properties, proxyUri);
        if (proxySelector != null) {
            requestFactoryBuilder = requestFactoryBuilder
                    .withHttpClientCustomizer(httpClient -> httpClient.proxy(
                            proxySelector
                    ));
        }
        return builder
                .requestFactoryBuilder(requestFactoryBuilder)
                .connectTimeout(Duration.ofMillis(
                        properties.getConnectTimeoutMs()
                ))
                .readTimeout(Duration.ofMillis(
                        properties.getReadTimeoutMs()
                ));
    }

    static ProxySelector proxySelector(
            OAuth2HttpClientProperties properties,
            URI proxyUri) {
        if (properties.getProxyMode()
                == OAuth2HttpClientProperties.ProxyMode.DIRECT) {
            return java.net.http.HttpClient.Builder.NO_PROXY;
        }
        if (proxyUri != null) {
            InetSocketAddress proxyAddress = InetSocketAddress.createUnresolved(
                    proxyUri.getHost(),
                    proxyUri.getPort()
            );
            return ProxySelector.of(proxyAddress);
        }
        return null;
    }
}
