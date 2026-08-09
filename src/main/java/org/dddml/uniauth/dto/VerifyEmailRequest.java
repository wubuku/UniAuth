package org.dddml.uniauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VerifyEmailRequest {

    @NotBlank
    @Size(max = 36)
    private String challengeHandle;

    @NotBlank
    @Size(max = 255)
    private String username;

    @NotBlank
    @Size(max = 320)
    private String email;

    @NotBlank
    @Size(max = 128)
    private String password;

    @Size(max = 255)
    private String displayName;

    @NotBlank
    @Pattern(regexp = "^[0-9]{6}$")
    private String verificationCode;
}
