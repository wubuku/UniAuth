package org.dddml.uniauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.dto.ChangePasswordRequest;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.LoginMethodConflictException;
import org.dddml.uniauth.service.LoginMethodService;
import org.dddml.uniauth.service.RecentAuthenticationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/user/password")
@RequiredArgsConstructor
public class PasswordController {

    private final LoginMethodService loginMethodService;
    private final RecentAuthenticationService recentAuthenticationService;
    private final AuthRateLimiter authRateLimiter;
    private final AuthCookieService authCookieService;

    @PutMapping
    public ResponseEntity<?> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String userId = jwt.getClaimAsString("userId");
        recentAuthenticationService.requireRecent(jwt);
        authRateLimiter.requireAllowed(
                AuthRateLimiter.Policy.LOGIN_METHOD_MUTATION,
                httpRequest.getRemoteAddr(),
                userId + "|password"
        );
        if (!request.newPassword().equals(request.newPasswordConfirm())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "PASSWORD_CONFIRMATION_MISMATCH"
            ));
        }
        try {
            loginMethodService.changePassword(
                    userId,
                    request.currentPassword(),
                    request.newPassword()
            );
            authCookieService.clearAuthenticationCookies(httpResponse);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Password changed; please sign in again"
            ));
        } catch (BadCredentialsException exception) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "error", "CURRENT_PASSWORD_INVALID"
            ));
        } catch (LoginMethodConflictException exception) {
            return ResponseEntity.status(409).body(Map.of(
                    "success", false,
                    "error", "PASSWORD_CHANGED_CONCURRENTLY"
            ));
        } catch (IllegalStateException exception) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "LOCAL_PASSWORD_NOT_CONFIGURED"
            ));
        }
    }
}
