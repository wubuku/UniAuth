package org.dddml.uniauth.service;

public class TokenRejectedException extends RuntimeException {

    public TokenRejectedException(String message) {
        super(message);
    }
}
