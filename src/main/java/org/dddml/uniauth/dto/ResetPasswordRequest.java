package org.dddml.uniauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置密码请求DTO
 */
@Data
public class ResetPasswordRequest {

    @NotBlank(message = "邮箱不能为空")
    @Size(max = 320)
    private String email;

    @NotBlank(message = "验证请求标识不能为空")
    @Size(max = 36)
    private String challengeHandle;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^[0-9]{6}$", message = "验证码必须是6位数字")
    private String verificationCode;

    @NotBlank(message = "新密码不能为空")
    @Size(max = 128, message = "密码长度不能超过128个字符")
    private String newPassword;
}
