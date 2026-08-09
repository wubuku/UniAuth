package org.dddml.uniauth.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "app.email.delivery")
@Validated
public class EmailDeliveryProperties {

    private boolean workerEnabled = true;

    @Min(100)
    @Max(600000)
    private long workerDelayMs = 1000;

    @Min(1)
    @Max(100)
    private int batchSize = 20;

    @Min(1)
    @Max(20)
    private int maxAttempts = 5;

    @Min(1)
    @Max(3600)
    private int baseRetrySeconds = 5;

    @Min(1)
    @Max(3600)
    private int processingTimeoutSeconds = 30;

    @Min(1)
    @Max(3600)
    private int deliveryDeadlineSeconds = 300;

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public long getWorkerDelayMs() {
        return workerDelayMs;
    }

    public void setWorkerDelayMs(long workerDelayMs) {
        this.workerDelayMs = workerDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getBaseRetrySeconds() {
        return baseRetrySeconds;
    }

    public void setBaseRetrySeconds(int baseRetrySeconds) {
        this.baseRetrySeconds = baseRetrySeconds;
    }

    public int getProcessingTimeoutSeconds() {
        return processingTimeoutSeconds;
    }

    public void setProcessingTimeoutSeconds(int processingTimeoutSeconds) {
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    public int getDeliveryDeadlineSeconds() {
        return deliveryDeadlineSeconds;
    }

    public void setDeliveryDeadlineSeconds(int deliveryDeadlineSeconds) {
        this.deliveryDeadlineSeconds = deliveryDeadlineSeconds;
    }

}
