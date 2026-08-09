package org.dddml.uniauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddLocalLoginRequest(
        @NotBlank(message = "Username is required")
        @Size(max = 255, message = "Username is too long")
        String username,
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128, message = "Password length is invalid")
        String password,
        @NotBlank(message = "Password confirmation is required")
        @Size(min = 8, max = 128, message = "Password confirmation length is invalid")
        String passwordConfirm) {
}
