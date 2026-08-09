package org.dddml.uniauth.dto;

public record LoginMethodMutationResponse(
        String message,
        String methodId,
        LoginMethodDto loginMethod) {
}
