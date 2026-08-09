package org.dddml.uniauth.service;

public class Web3BindingConflictException extends RuntimeException {

    public Web3BindingConflictException() {
        super("Web3 credential could not be bound");
    }
}
