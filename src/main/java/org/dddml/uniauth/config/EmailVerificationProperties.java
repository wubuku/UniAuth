package org.dddml.uniauth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "app.email.verification")
@Validated
public class EmailVerificationProperties {

    @Min(1)
    @Max(10)
    private int codeLength = 6;

    @Min(1)
    @Max(10080)
    private int expiryMinutes = 10;

    @Min(1)
    private int maxSendPerDay = 10;

    @Min(1)
    private int maxRetryAttempts = 5;

    @Min(0)
    @Max(86400)
    private int resendCooldownSeconds = 60;

    public int getCodeLength() {
        return codeLength;
    }

    public void setCodeLength(int codeLength) {
        this.codeLength = codeLength;
    }

    public int getExpiryMinutes() {
        return expiryMinutes;
    }

    public void setExpiryMinutes(int expiryMinutes) {
        this.expiryMinutes = expiryMinutes;
    }

    public int getMaxSendPerDay() {
        return maxSendPerDay;
    }

    public void setMaxSendPerDay(int maxSendPerDay) {
        this.maxSendPerDay = maxSendPerDay;
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        this.maxRetryAttempts = maxRetryAttempts;
    }

    public int getResendCooldownSeconds() {
        return resendCooldownSeconds;
    }

    public void setResendCooldownSeconds(int resendCooldownSeconds) {
        this.resendCooldownSeconds = resendCooldownSeconds;
    }
}
