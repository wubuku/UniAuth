package org.dddml.uniauth.service.email;

public enum EmailSendResult {
    SUCCESS,
    QUEUED,
    FAILED,
    RATE_LIMITED,
    INVALID_EMAIL
}
