package org.dddml.uniauth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.oauth2.http")
@Validated
@Getter
@Setter
public class OAuth2HttpClientProperties {

    @Min(100)
    @Max(60_000)
    private long connectTimeoutMs = 5_000;

    @Min(100)
    @Max(60_000)
    private long readTimeoutMs = 10_000;
}
