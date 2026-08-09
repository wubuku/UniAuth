package org.dddml.uniauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendVerificationCodeRequest {

    @NotBlank
    @Size(max = 320)
    private String email;

    @Size(max = 32)
    private String purpose = "REGISTRATION";
}
