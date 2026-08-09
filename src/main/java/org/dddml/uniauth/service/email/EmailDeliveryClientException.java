package org.dddml.uniauth.service.email;

public class EmailDeliveryClientException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public EmailDeliveryClientException(
            String errorCode,
            boolean retryable,
            Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public EmailDeliveryClientException(String errorCode, boolean retryable) {
        this(errorCode, retryable, null);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
