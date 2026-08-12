package org.dddml.uniauth.controller;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.AuthenticationLogoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * REST API控制器 - 前后端分离架构
 * 提供JSON API接口，不涉及视图渲染
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ApiAuthController {

    private final UserRepository userRepository;
    private final AuthCookieService authCookieService;
    private final AuthenticationLogoutService authenticationLogoutService;
    private final UserLoginMethodRepository loginMethodRepository;

    /**
     * 获取当前用户信息
     * GET /api/user
     */
    @GetMapping("/user")
    public ResponseEntity<?> getCurrentUser(@AuthenticationPrincipal Object principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "User not authenticated"));
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("authenticated", true);

        if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
            String userId = jwt.getClaim("userId");
            String username = jwt.getClaim("username");
            if (username == null) {
                username = jwt.getSubject();
            }

            UserEntity user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "User does not exist"
                    ));
            String actualProvider = loginMethodRepository
                    .findByUserIdAndIsPrimary(userId, true)
                    .map(method -> method.getAuthProvider()
                            == org.dddml.uniauth.entity.UserLoginMethod.AuthProvider.TWITTER
                            ? "x"
                            : method.getAuthProvider().name().toLowerCase())
                    .orElse("unknown");

            userInfo.put("provider", actualProvider);
            userInfo.put("userId", userId);
            userInfo.put("userName", username);
            userInfo.put("userEmail", user.getEmail());
            userInfo.put("userAvatar", user.getAvatarUrl());
            userInfo.put(
                    "hasLocalPassword",
                    loginMethodRepository
                            .findByUserIdAndAuthProvider(
                                    userId,
                                    org.dddml.uniauth.entity.UserLoginMethod
                                            .AuthProvider.LOCAL
                            )
                            .map(method -> method.getLocalPasswordHash() != null
                                    && !method.getLocalPasswordHash().isBlank())
                            .orElse(false)
            );
            userInfo.put("providerInfo", new HashMap<>());
            return ResponseEntity.ok(userInfo);
        }

        return ResponseEntity.status(401).body(Map.of(
                "error",
                "User not authenticated"
        ));
    }

    /**
     * 登出接口
     * POST /api/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        AuthenticationLogoutService.LogoutResult result =
                authenticationLogoutService.logout(request, response);
        if (!result.revocationComplete()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "REVOCATION_INCOMPLETE",
                    "message", "Logged out locally; token revocation is incomplete"
            ));
        }
        log.info("API logout completed");
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * 获取OAuth2登录URL
     * GET /api/oauth2/authorization/{provider}
     * 注意：这个实际上会被Spring Security处理，这里只是为了文档
     */

}
