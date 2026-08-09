package org.dddml.uniauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.auth.cookie")
public class AuthCookieProperties {

    private boolean secure;
    private String accessTokenName = "accessToken";
    private String refreshTokenName = "refreshToken";

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getAccessTokenName() {
        return accessTokenName;
    }

    public void setAccessTokenName(String accessTokenName) {
        this.accessTokenName = accessTokenName;
    }

    public String getRefreshTokenName() {
        return refreshTokenName;
    }

    public void setRefreshTokenName(String refreshTokenName) {
        this.refreshTokenName = refreshTokenName;
    }
}
