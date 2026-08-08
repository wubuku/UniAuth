package org.dddml.email.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.security")
@Getter
@Setter
public class EmailSecurityProperties {

    public static final String API_KEY_HEADER = "X-Email-Service-Key";

    private String apiKey = "";
}
