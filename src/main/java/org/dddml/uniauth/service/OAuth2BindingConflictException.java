package org.dddml.uniauth.service;

public class OAuth2BindingConflictException
        extends LoginMethodConflictException {

    public OAuth2BindingConflictException(String message) {
        super(message);
    }
}
