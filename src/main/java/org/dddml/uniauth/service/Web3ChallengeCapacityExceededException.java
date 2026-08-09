package org.dddml.uniauth.service;

public class Web3ChallengeCapacityExceededException extends RuntimeException {

    public Web3ChallengeCapacityExceededException() {
        super("Web3 challenge capacity is temporarily exhausted");
    }
}
