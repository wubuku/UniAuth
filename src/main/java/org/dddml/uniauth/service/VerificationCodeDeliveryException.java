package org.dddml.uniauth.service;

import org.dddml.uniauth.service.email.EmailSendResult;

import java.util.Objects;

public class VerificationCodeDeliveryException extends RuntimeException {

    private final EmailSendResult result;

    public VerificationCodeDeliveryException(EmailSendResult result) {
        super("Email service did not accept the verification request");
        this.result = Objects.requireNonNull(result);
    }

    public VerificationCodeDeliveryException(
            EmailSendResult result,
            Throwable cause) {
        super("Email service did not accept the verification request", cause);
        this.result = Objects.requireNonNull(result);
    }

    public EmailSendResult getResult() {
        return result;
    }
}
