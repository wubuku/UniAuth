package org.dddml.uniauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth.transport")
public class AuthTransportProperties {

    private boolean exposeAccessToken;
    private boolean diagnosticsEnabled;
}
