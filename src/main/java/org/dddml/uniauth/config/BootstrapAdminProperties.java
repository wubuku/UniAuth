package org.dddml.uniauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Explicit bootstrap credentials for the first local administrator.
 *
 * The default is disabled so the application never creates a predictable
 * administrator account or embeds a production password.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.bootstrap-admin")
public class BootstrapAdminProperties {

    private boolean enabled;
    private String username = "";
    private String email = "";
    private String password = "";
    private String displayName = "Administrator";
}
