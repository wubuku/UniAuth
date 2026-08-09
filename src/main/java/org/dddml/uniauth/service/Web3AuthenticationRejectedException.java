package org.dddml.uniauth.service;

public class Web3AuthenticationRejectedException extends RuntimeException {

    public Web3AuthenticationRejectedException() {
        super("Web3 authentication was rejected");
    }
}
