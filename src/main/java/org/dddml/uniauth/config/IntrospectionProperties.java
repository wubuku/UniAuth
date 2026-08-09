package org.dddml.uniauth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "app.auth.introspection")
public class IntrospectionProperties {

    @NotBlank
    @Size(max = 128)
    private String clientId;

    @NotBlank
    @Size(min = 32, max = 1024)
    private String clientSecret;
}
