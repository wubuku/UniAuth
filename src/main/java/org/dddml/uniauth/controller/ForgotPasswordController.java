package org.dddml.uniauth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.dto.ForgotPasswordRequest;
import org.dddml.uniauth.dto.ResetPasswordRequest;
import org.dddml.uniauth.service.ForgotPasswordService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "认证相关接口")
public class ForgotPasswordController {

    private final ForgotPasswordService forgotPasswordService;

    @PostMapping("/forgot-password")
    @Operation(
        summary = "请求密码重置验证码",
        description = "向用户邮箱发送密码重置验证码",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "验证码已发送",
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                        example = "{\"success\": true, \"message\": \"验证码已发送到邮箱\", \"resendAfter\": 60, \"expiresIn\": 600}"
                    )
                )
            ),
            @ApiResponse(
                responseCode = "400",
                description = "请求失败",
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                        example = "{\"success\": false, \"message\": \"发送失败，请稍后重试\"}"
                    )
                )
            )
        }
    )
    public ResponseEntity<?> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        log.info("Received forgot password request for email: {}", request.getEmail());

        try {
            if (!forgotPasswordService.canSend(request.getEmail())) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "请求过于频繁，请稍后再试"
                ));
            }

            boolean sent = forgotPasswordService.sendPasswordResetCode(request.getEmail());
            if (!sent) {
                return ResponseEntity.ok(Map.of(
                    "success", false,
                    "errorCode", "EMAIL_NOT_REGISTERED",
                    "message", "该邮箱未注册，请先完成注册"
                ));
            }

            long cooldown = forgotPasswordService.getResendCooldown(request.getEmail());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "验证码已发送到邮箱",
                "resendAfter", cooldown,
                "expiresIn", 600
            ));
        } catch (Exception e) {
            log.error("Failed to send password reset code", e);
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "发送失败，请稍后重试"
            ));
        }
    }

    @PostMapping("/verify-reset-code")
    @Operation(
        summary = "验证验证码并重置密码",
        description = "验证密码重置验证码，如果正确则更新密码",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "密码重置成功",
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                        example = "{\"success\": true, \"message\": \"密码重置成功，请使用新密码登录\"}"
                    )
                )
            ),
            @ApiResponse(
                responseCode = "400",
                description = "验证失败",
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(
                        example = "{\"success\": false, \"message\": \"验证码错误\"}"
                    )
                )
            )
        }
    )
    public ResponseEntity<?> verifyResetCode(@Valid @RequestBody ResetPasswordRequest request) {
        log.info("Received reset password request for email: {}", request.getEmail());

        try {
            forgotPasswordService.resetPassword(
                request.getEmail(),
                request.getVerificationCode(),
                request.getNewPassword()
            );

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "密码重置成功，请使用新密码登录"
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Password reset failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", e.getMessage()
            ));
        } catch (Exception e) {
            log.error("Password reset failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "message", "密码重置失败，请稍后重试"
            ));
        }
    }
}
