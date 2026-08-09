package org.dddml.uniauth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户注册请求DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {
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

    @Size(max = 6)
    private String verificationCode;

    @Size(max = 36)
    private String challengeHandle;
}
