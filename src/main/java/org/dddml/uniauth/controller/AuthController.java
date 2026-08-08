package org.dddml.uniauth.controller;

import org.dddml.uniauth.config.EmailRegistrationProperties;
import org.dddml.uniauth.dto.RegisterRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.EmailVerificationCodeService;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.entity.EmailVerificationCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/**
 * 认证相关API控制器和SPA路由控制器
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "用户认证相关API")
@Slf4j
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final UserLoginMethodRepository loginMethodRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final EmailRegistrationProperties emailRegistrationProperties;
    private final EmailVerificationCodeService verificationCodeService;
    private final AuthCookieService authCookieService;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private boolean isValidEmail(String username) {
        return EMAIL_PATTERN.matcher(username).matches();
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    @Operation(
        summary = "用户注册",
        description = "创建新的本地用户账号",
        tags = { "Authentication" }
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "注册成功",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDto.class)
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "注册失败",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"error\": \"Username already exists\"}"
                )
            )
        )
    })
    @Transactional
    public ResponseEntity<?> register(
            @Parameter(
                name = "request",
                description = "注册信息",
                required = true
            )
            @RequestBody RegisterRequest request,
            HttpServletResponse response) {
        boolean isEmailUsername = isValidEmail(request.getUsername());

        if (isEmailUsername) {
            return handleEmailRegistration(request, response);
        } else {
            if (emailRegistrationProperties.isRequireEmailUsername()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "REGISTRATION_NOT_ALLOWED",
                    "message", "Only email addresses are allowed for registration",
                    "requireEmailUsername", true
                ));
            }
            UserDto user = userService.register(request);
            return ResponseEntity.ok(user);
        }
    }

    private ResponseEntity<?> handleEmailRegistration(
            RegisterRequest request,
            HttpServletResponse response) {
        if (loginMethodRepository.existsByLocalUsername(request.getUsername())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "EMAIL_ALREADY_REGISTERED",
                "message", "该邮箱已注册，请使用忘记密码功能重置密码",
                "errorCode", "EMAIL_EXISTS"
            ));
        }

        String requestEmail = request.getEmail();
        String username = request.getUsername();

        if (requestEmail != null && !requestEmail.equalsIgnoreCase(username)) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "EMAIL_MISMATCH",
                "message", "用户名和邮箱必须一致"
            ));
        }

        String verificationCode = request.getVerificationCode();
        if (verificationCode != null && !verificationCode.isEmpty()) {
            return verifyAndRegisterWithCode(request, username, response);
        }

        return ResponseEntity.ok(Map.of(
            "requireEmailVerification", true,
            "username", username,
            "message", "请完成邮箱验证以完成注册"
        ));
    }

    private ResponseEntity<?> verifyAndRegisterWithCode(
            RegisterRequest request,
            String email,
            HttpServletResponse response) {
        var result = verificationCodeService.verifyCode(
            email,
            request.getVerificationCode(),
            EmailVerificationCode.VerificationPurpose.REGISTRATION
        );

        if (!result.isSuccess()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INVALID_CODE",
                "message", result.getError(),
                "remainingAttempts", result.getRemainingAttempts()
            ));
        }

        Map<String, Object> metadata = result.getMetadata();
        if (metadata == null) {
            metadata = new HashMap<>();
        }

        String password = request.getPassword();
        if (password != null && !password.isEmpty()) {
            metadata.put("password", passwordEncoder.encode(password));
        }

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            displayName = extractDisplayNameFromEmail(email);
        }
        metadata.put("displayName", displayName);

        UserDto user = userService.registerWithEmailVerification(email, metadata);

        String accessToken = jwtTokenService.generateAccessToken(
            user.getUsername(),
            user.getEmail(),
            user.getId(),
            user.getAuthorities()
        );

        String refreshToken = jwtTokenService.generateRefreshToken(
            user.getUsername(),
            user.getId()
        );
        authCookieService.writeTokenCookies(response, accessToken, refreshToken);

        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("user", user);
        responseData.put("message", "Registration completed successfully");
        responseData.put("accessToken", accessToken);
        responseData.put("refreshToken", refreshToken);
        responseData.put("accessTokenExpiresIn", 3600);
        responseData.put("refreshTokenExpiresIn", 604800);
        responseData.put("tokenType", "Bearer");

        return ResponseEntity.ok(responseData);
    }

    private String extractDisplayNameFromEmail(String email) {
        return email.split("@")[0];
    }

    /**
     * 本地用户登录
     * 使用Spring Security进行认证并建立会话
     */
    @PostMapping("/login")
    @Operation(
        summary = "本地用户登录",
        description = "使用用户名和密码登录，支持Token双重传递（cookie + JSON响应体）",
        tags = { "Authentication" }
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "登录成功",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"message\": \"Login successful\", \"authenticated\": true, \"user\": {...}, \"accessToken\": \"...\", \"refreshToken\": \"...\", \"accessTokenExpiresIn\": 3600, \"refreshTokenExpiresIn\": 604800, \"tokenType\": \"Bearer\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "登录失败",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"error\": \"Invalid credentials\"}"
                )
            )
        )
    })
    public ResponseEntity<?> login(
            @Parameter(
                name = "username",
                description = "用户名",
                required = true
            )
            @RequestParam String username,
            @Parameter(
                name = "password",
                description = "密码",
                required = true
            )
            @RequestParam String password,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            // 使用AuthenticationManager进行认证
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password)
            );

            // 认证成功，建立SecurityContext
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // 获取用户信息
            UserDto user = userService.login(username, password);

            // 生成JWT Token
            String accessToken = jwtTokenService.generateAccessToken(
                user.getUsername(),
                user.getEmail(),
                user.getId(),
                userService.getCurrentUser(user.getUsername()).getAuthorities()
            );

            String refreshToken = jwtTokenService.generateRefreshToken(
                user.getUsername(),
                user.getId()
            );

            authCookieService.writeTokenCookies(response, accessToken, refreshToken);

            // 返回成功响应（包含Token用于跨域场景）
            Map<String, Object> responseData = new java.util.LinkedHashMap<>();
            responseData.put("user", user);
            responseData.put("message", "Login successful");
            responseData.put("authenticated", true);
            responseData.put("accessToken", accessToken);
            responseData.put("refreshToken", refreshToken);
            responseData.put("accessTokenExpiresIn", 3600);  // 1小时
            responseData.put("refreshTokenExpiresIn", 604800);  // 7天
            responseData.put("tokenType", "Bearer");

            return ResponseEntity.ok(responseData);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "Invalid credentials"));
        }
    }

    /**
     * 登出 - 统一认证方式的登出处理
     */
    @PostMapping("/logout")
    @Operation(
        summary = "用户登出",
        description = "清除用户认证状态和所有认证相关的Cookies",
        tags = { "Authentication" }
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "登出成功",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"message\": \"Logged out successfully\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "500",
            description = "登出失败",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"error\": \"Logout failed\"}"
                )
            )
        )
    })
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        try {
            // 1. 清除Spring Security上下文
            SecurityContextHolder.clearContext();

            // 2. 使用SecurityContextLogoutHandler处理OAuth2特定的清理
            new SecurityContextLogoutHandler().logout(request, response, null);

            // 3. 使Session无效（如果存在）
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }

            // 4. ✅ 关键：清除所有认证Cookies！
            authCookieService.clearAuthenticationCookies(response);

            log.info("Authentication logout completed");
            return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
        } catch (Exception e) {
            log.warn("Authentication logout encountered an error");
            return ResponseEntity.status(500).body(Map.of("error", "Logout failed"));
        }
    }

}
