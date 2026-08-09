package org.dddml.uniauth.service;

public class VerificationChallengeRejectedException
        extends IllegalArgumentException {

    public VerificationChallengeRejectedException() {
        super("Invalid or expired verification challenge");
    }
}
