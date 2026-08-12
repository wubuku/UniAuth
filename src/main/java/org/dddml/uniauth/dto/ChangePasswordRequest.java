package org.dddml.uniauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "Current password is required")
        @Size(max = 128, message = "Current password is too long")
        String currentPassword,
        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 128, message = "New password length is invalid")
        String newPassword,
        @NotBlank(message = "Password confirmation is required")
        @Size(min = 8, max = 128, message = "Password confirmation length is invalid")
        String newPasswordConfirm) {
}
