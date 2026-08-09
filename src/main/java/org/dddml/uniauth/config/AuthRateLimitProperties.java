package org.dddml.uniauth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "app.auth.rate-limit")
@Validated
public class AuthRateLimitProperties {

    private boolean enabled = true;

    @Min(1)
    @Max(86400)
    private int windowSeconds = 60;

    @Min(1)
    @Max(10000)
    private int sourceLimit = 60;

    @Min(1)
    @Max(10000)
    private int loginLimit = 10;

    @Min(1)
    @Max(10000)
    private int registrationLimit = 10;

    @Min(1)
    @Max(10000)
    private int challengeSendLimit = 5;

    @Min(1)
    @Max(10000)
    private int challengeVerifyLimit = 10;

    @Min(1)
    @Max(10000)
    private int passwordResetLimit = 5;

    @Min(1)
    @Max(10000)
    private int refreshLimit = 20;

    @Min(1)
    @Max(10000)
    private int introspectionLimit = 60;

    @Min(1)
    @Max(10000)
    private int oauthAuthorizeLimit = 20;

    @Min(1)
    @Max(10000)
    private int web3ChallengeLimit = 10;

    @Min(1)
    @Max(10000)
    private int web3VerifyLimit = 20;

    @Min(1)
    @Max(10000)
    private int loginMethodMutationLimit = 20;

    @NotBlank
    @Size(min = 32, max = 1024)
    private String keySecret = "local-only-auth-rate-limit-key-change-me";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getSourceLimit() {
        return sourceLimit;
    }

    public void setSourceLimit(int sourceLimit) {
        this.sourceLimit = sourceLimit;
    }

    public int getLoginLimit() {
        return loginLimit;
    }

    public void setLoginLimit(int loginLimit) {
        this.loginLimit = loginLimit;
    }

    public int getRegistrationLimit() {
        return registrationLimit;
    }

    public void setRegistrationLimit(int registrationLimit) {
        this.registrationLimit = registrationLimit;
    }

    public int getChallengeSendLimit() {
        return challengeSendLimit;
    }

    public void setChallengeSendLimit(int challengeSendLimit) {
        this.challengeSendLimit = challengeSendLimit;
    }

    public int getChallengeVerifyLimit() {
        return challengeVerifyLimit;
    }

    public void setChallengeVerifyLimit(int challengeVerifyLimit) {
        this.challengeVerifyLimit = challengeVerifyLimit;
    }

    public int getPasswordResetLimit() {
        return passwordResetLimit;
    }

    public void setPasswordResetLimit(int passwordResetLimit) {
        this.passwordResetLimit = passwordResetLimit;
    }

    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public int getRefreshLimit() {
        return refreshLimit;
    }

    public void setRefreshLimit(int refreshLimit) {
        this.refreshLimit = refreshLimit;
    }

    public int getIntrospectionLimit() {
        return introspectionLimit;
    }

    public void setIntrospectionLimit(int introspectionLimit) {
        this.introspectionLimit = introspectionLimit;
    }

    public int getOauthAuthorizeLimit() {
        return oauthAuthorizeLimit;
    }

    public void setOauthAuthorizeLimit(int oauthAuthorizeLimit) {
        this.oauthAuthorizeLimit = oauthAuthorizeLimit;
    }

    public int getWeb3ChallengeLimit() {
        return web3ChallengeLimit;
    }

    public void setWeb3ChallengeLimit(int web3ChallengeLimit) {
        this.web3ChallengeLimit = web3ChallengeLimit;
    }

    public int getWeb3VerifyLimit() {
        return web3VerifyLimit;
    }

    public void setWeb3VerifyLimit(int web3VerifyLimit) {
        this.web3VerifyLimit = web3VerifyLimit;
    }

    public int getLoginMethodMutationLimit() {
        return loginMethodMutationLimit;
    }

    public void setLoginMethodMutationLimit(int loginMethodMutationLimit) {
        this.loginMethodMutationLimit = loginMethodMutationLimit;
    }
}
