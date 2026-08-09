package org.dddml.uniauth.service;

public class RecentAuthenticationRequiredException extends RuntimeException {

    public RecentAuthenticationRequiredException() {
        super("Recent authentication is required");
    }
}
