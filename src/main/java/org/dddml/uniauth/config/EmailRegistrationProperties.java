package org.dddml.uniauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.email.registration")
public class EmailRegistrationProperties {

    private boolean requireEmailUsername = false;

    public boolean isRequireEmailUsername() {
        return requireEmailUsername;
    }

    public void setRequireEmailUsername(boolean requireEmailUsername) {
        this.requireEmailUsername = requireEmailUsername;
    }
}
