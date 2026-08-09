package org.dddml.uniauth.controller;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.UserRepository;
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

        // 处理OAuth2用户（第三方登录）
        if (principal instanceof OAuth2User oauth2User) {
            // 基础用户信息
            String provider = getProviderFromUser(oauth2User);
            userInfo.put("provider", provider);
            userInfo.put("userName", getUserName(oauth2User, provider));
            userInfo.put("userEmail", getUserEmail(oauth2User, provider));
            userInfo.put("userId", getUserId(oauth2User, provider));
            userInfo.put("userAvatar", getUserAvatar(oauth2User, provider));

            // 提供商特定信息
            Map<String, Object> providerInfo = new HashMap<>();
            switch (provider) {
                case "google":
                    providerInfo.put("sub", oauth2User.getAttribute("sub"));
                    break;
                case "github":
                    providerInfo.put("htmlUrl", getUserHtmlUrl(oauth2User, provider));
                    providerInfo.put("publicRepos", oauth2User.getAttribute("public_repos"));
                    providerInfo.put("followers", oauth2User.getAttribute("followers"));
                    break;
                case "x":  // ✅ 使用注册ID 'x'，与 OAuth2 配置一致
                    providerInfo.put("location", getUserLocation(oauth2User));
                    providerInfo.put("verified", getUserVerified(oauth2User));
                    providerInfo.put("description", getUserDescription(oauth2User));
                    break;
            }
            userInfo.put("providerInfo", providerInfo);
        }
        // 处理JWT用户（本地登录或OAuth2登录后的JWT认证）
        else if (principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
            String userId = jwt.getClaim("userId");
            // 优先从 username claim 获取用户名（新版Token），兼容旧版Token（sub即用户名）
            String username = jwt.getClaim("username");
            if (username == null) {
                username = jwt.getSubject();
            }

            // 从数据库查询用户信息（provider信息已在JWT中）
            UserEntity user = userRepository.findById(userId).orElse(null);
            String actualProvider = "local"; // 默认值

            userInfo.put("provider", actualProvider);
            userInfo.put("userId", userId);
            userInfo.put("userName", username);
            userInfo.put("userEmail", jwt.getClaim("email"));
            userInfo.put("userAvatar", user != null ? user.getAvatarUrl() : null);
            userInfo.put("providerInfo", new HashMap<>());
        }
        // 处理其他类型的认证主体
        else {
            userInfo.put("provider", "unknown");
            userInfo.put("userName", principal.toString());
            userInfo.put("userEmail", null);
            userInfo.put("userId", null);
            userInfo.put("userAvatar", null);
            userInfo.put("providerInfo", new HashMap<>());
        }

        return ResponseEntity.ok(userInfo);
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

    // ===== 辅助方法 =====

    private String getProviderFromUser(OAuth2User oauth2User) {
        if (oauth2User.getAttribute("sub") != null) {
            return "google";
        } else if (oauth2User.getAttribute("login") != null) {
            return "github";
        } else if (oauth2User.getAttribute("username") != null) {
            return "x";  // ✅ 返回注册ID 'x'，与 OAuth2 配置一致
        }
        return "unknown";
    }

    private String getUserName(OAuth2User oauth2User, String provider) {
        switch (provider) {
            case "google": return oauth2User.getAttribute("name");
            case "github": return oauth2User.getAttribute("login");
            case "x": return oauth2User.getAttribute("username");  // ✅ 使用注册ID 'x'
            default: return oauth2User.getName();
        }
    }

    private String getUserEmail(OAuth2User oauth2User, String provider) {
        switch (provider) {
            case "google": return oauth2User.getAttribute("email");
            case "github": return oauth2User.getAttribute("email");
            case "x": return null; // X/Twitter不提供email
            default: return null;
        }
    }

    private String getUserId(OAuth2User oauth2User, String provider) {
        switch (provider) {
            case "google": return oauth2User.getAttribute("sub");
            case "github":
                Object id = oauth2User.getAttribute("id");
                return id != null ? id.toString() : null;
            case "x": return oauth2User.getAttribute("id");  // ✅ 使用注册ID 'x'
            default: return null;
        }
    }

    private String getUserAvatar(OAuth2User oauth2User, String provider) {
        switch (provider) {
            case "google": return oauth2User.getAttribute("picture");
            case "github": return oauth2User.getAttribute("avatar_url");
            case "x": return oauth2User.getAttribute("profile_image_url");  // ✅ 使用注册ID 'x'
            default: return null;
        }
    }

    private String getUserHtmlUrl(OAuth2User oauth2User, String provider) {
        if ("github".equals(provider)) {
            return oauth2User.getAttribute("html_url");
        }
        return null;
    }

    private String getUserLocation(OAuth2User oauth2User) {
        return oauth2User.getAttribute("location");
    }

    private Boolean getUserVerified(OAuth2User oauth2User) {
        return oauth2User.getAttribute("verified");
    }

    private String getUserDescription(OAuth2User oauth2User) {
        return oauth2User.getAttribute("description");
    }

}
