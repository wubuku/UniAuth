package org.dddml.uniauth.service;

public class LoginMethodConflictException extends IllegalStateException {

    public LoginMethodConflictException(String message) {
        super(message);
    }
}
