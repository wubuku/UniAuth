package org.dddml.uniauth.service;

public class OAuth2BindingIntentRejectedException extends RuntimeException {

    public OAuth2BindingIntentRejectedException() {
        super("OAuth2 binding intent was rejected");
    }
}
