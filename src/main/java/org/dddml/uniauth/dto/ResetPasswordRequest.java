package org.dddml.uniauth.dto;

import jakarta.validation.constraints.Email;
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
    @Email(message = "请输入有效的邮箱地址")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^[0-9]{6}$", message = "验证码必须是6位数字")
    private String verificationCode;

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 128, message = "密码长度必须在8-128个字符之间")
    private String newPassword;
}
