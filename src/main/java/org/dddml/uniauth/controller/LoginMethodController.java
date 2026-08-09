package org.dddml.uniauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.dddml.uniauth.dto.AddLocalLoginRequest;
import org.dddml.uniauth.dto.LoginMethodDto;
import org.dddml.uniauth.dto.LoginMethodMutationResponse;
import org.dddml.uniauth.dto.LoginMethodsResponse;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.AuthRateLimitExceededException;
import org.dddml.uniauth.service.AuthRateLimiterUnavailableException;
import org.dddml.uniauth.service.LoginMethodConflictException;
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.RecentAuthenticationRequiredException;
import org.dddml.uniauth.service.RecentAuthenticationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 登录方式管理控制器
 * 提供查询、移除、设置主登录方式的功能
 */
@RestController
@RequestMapping("/api/user/login-methods")
@RequiredArgsConstructor
@Slf4j
public class LoginMethodController {

    private final LoginMethodService loginMethodService;
    private final RecentAuthenticationService recentAuthenticationService;
    private final AuthRateLimiter authRateLimiter;

    /**
     * 获取当前用户的登录方式列表
     * GET /api/user/login-methods
     */
    @GetMapping
    public ResponseEntity<LoginMethodsResponse> getLoginMethods(
            @AuthenticationPrincipal Jwt jwt) {
        try {
            String userId = jwt.getClaim("userId");
            
            List<UserLoginMethod> methods = loginMethodService.getUserLoginMethods(userId);
            
            List<LoginMethodDto> methodDtos = methods.stream()
                .map(this::convertToDto)
                .toList();
            
            return ResponseEntity.ok(new LoginMethodsResponse(
                    methodDtos,
                    methodDtos.size()
            ));
        } catch (Exception e) {
            log.error("Failed to get login methods");
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * 移除登录方式
     * DELETE /api/user/login-methods/{methodId}
     */
    @DeleteMapping("/{methodId}")
    public ResponseEntity<?> removeLoginMethod(
            @PathVariable String methodId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        try {
            String userId = jwt.getClaim("userId");
            requireSensitiveMutation(jwt, request, userId, "remove");
            loginMethodService.removeLoginMethod(userId, methodId);
            
            return ResponseEntity.ok(new LoginMethodMutationResponse(
                    "登录方式已移除",
                    methodId,
                    null
            ));
        } catch (RecentAuthenticationRequiredException
                 | AuthRateLimitExceededException
                 | AuthRateLimiterUnavailableException e) {
            throw e;
        } catch (LoginMethodConflictException e) {
            log.warn("Concurrent login method removal conflict: {}", e.getMessage());
            return ResponseEntity.status(409).body(
                Map.of("error", e.getMessage())
            );
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("Failed to remove login method: {}", e.getMessage());
            return ResponseEntity.status(400).body(
                Map.of("error", e.getMessage())
            );
        } catch (Exception e) {
            log.error("Failed to remove login method");
            return ResponseEntity.status(500).body(
                Map.of("error", "移除登录方式失败")
            );
        }
    }

    /**
     * 设置主登录方式
     * PUT /api/user/login-methods/{methodId}/primary
     */
    @PutMapping("/{methodId}/primary")
    public ResponseEntity<?> setPrimaryLoginMethod(
            @PathVariable String methodId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        try {
            String userId = jwt.getClaim("userId");
            requireSensitiveMutation(jwt, request, userId, "primary");
            loginMethodService.setPrimaryLoginMethod(userId, methodId);
            
            return ResponseEntity.ok(new LoginMethodMutationResponse(
                    "主登录方式已设置",
                    methodId,
                    null
            ));
        } catch (RecentAuthenticationRequiredException
                 | AuthRateLimitExceededException
                 | AuthRateLimiterUnavailableException e) {
            throw e;
        } catch (LoginMethodConflictException e) {
            log.warn("Concurrent primary login method update: {}", e.getMessage());
            return ResponseEntity.status(409).body(
                Map.of("error", e.getMessage())
            );
        } catch (IllegalArgumentException e) {
            log.warn("Failed to set primary login method: {}", e.getMessage());
            return ResponseEntity.status(400).body(
                Map.of("error", e.getMessage())
            );
        } catch (Exception e) {
            log.error("Failed to set primary login method");
            return ResponseEntity.status(500).body(
                Map.of("error", "设置主登录方式失败")
            );
        }
    }

    /**
     * 为SSO用户添加本地登录方式
     * POST /api/user/add-local-login
     * 
     * 请求体:
     * {
     *   "username": "myusername",
     *   "password": "mypassword",
     *   "passwordConfirm": "mypassword"
     * }
     */
    @PostMapping("/add-local-login")
    public ResponseEntity<?> addLocalLoginMethod(
            @Valid @RequestBody AddLocalLoginRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        try {
            String userId = jwt.getClaim("userId");
            requireSensitiveMutation(jwt, httpRequest, userId, "add-local");
            if (!request.password().equals(request.passwordConfirm())) {
                return ResponseEntity.status(400).body(
                    Map.of("error", "两次输入的密码不一致")
                );
            }
            var loginMethod = loginMethodService.addLocalLoginMethod(
                    userId,
                    request.username(),
                    request.password()
            );
            
            return ResponseEntity.ok(new LoginMethodMutationResponse(
                    "本地登录方式添加成功",
                    loginMethod.getId(),
                    convertToDto(loginMethod)
            ));
            
        } catch (RecentAuthenticationRequiredException
                 | AuthRateLimitExceededException
                 | AuthRateLimiterUnavailableException e) {
            throw e;
        } catch (IllegalStateException e) {
            log.warn("Failed to add local login: {}", e.getMessage());
            return ResponseEntity.status(400).body(
                Map.of("error", e.getMessage())
            );
        } catch (IllegalArgumentException e) {
            log.warn("Invalid input: {}", e.getMessage());
            return ResponseEntity.status(400).body(
                Map.of("error", e.getMessage())
            );
        } catch (Exception e) {
            log.error("Failed to add local login method");
            return ResponseEntity.status(500).body(
                Map.of("error", "添加本地登录方式失败")
            );
        }
    }

    /**
     * 转换为DTO
     */
    private LoginMethodDto convertToDto(UserLoginMethod method) {
        return new LoginMethodDto(
                method.getId(),
                method.getAuthProvider() == UserLoginMethod.AuthProvider.TWITTER
                        ? "x"
                        : method.getAuthProvider().name().toLowerCase(),
                method.isPrimary(),
                method.isVerified(),
                method.getLinkedAt(),
                method.getLastUsedAt(),
                method.isOAuth2Method() ? method.getProviderEmail() : null,
                method.isOAuth2Method() ? method.getProviderUsername() : null,
                method.isLocalMethod() ? method.getLocalUsername() : null
        );
    }

    private void requireSensitiveMutation(
            Jwt jwt,
            HttpServletRequest request,
            String userId,
            String operation) {
        recentAuthenticationService.requireRecent(jwt);
        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.LOGIN_METHOD_MUTATION,
                request.getRemoteAddr(),
                userId + "|" + operation
        );
    }
}
