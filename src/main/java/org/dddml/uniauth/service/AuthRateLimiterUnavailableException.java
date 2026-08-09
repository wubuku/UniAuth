package org.dddml.uniauth.service;

public class AuthRateLimiterUnavailableException extends RuntimeException {

    public AuthRateLimiterUnavailableException(Throwable cause) {
        super("Authentication rate limiter is unavailable", cause);
    }
}
