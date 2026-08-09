package org.dddml.uniauth.controller;

import org.dddml.uniauth.service.TokenRefreshService;
import org.dddml.uniauth.service.AuthCookieService;
import org.dddml.uniauth.service.TokenRejectedException;
import org.dddml.uniauth.config.AuthTransportProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Token管理控制器
 * 处理JWT Token的刷新和其他token相关操作
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Token Management", description = "JWT Token管理相关API")
public class TokenController {

    private final TokenRefreshService tokenRefreshService;
    private final AuthCookieService authCookieService;
    private final AuthTransportProperties transportProperties;
    private final org.dddml.uniauth.service.AuthenticationCredentialResolver
            credentialResolver;
    private final org.dddml.uniauth.service.AuthRateLimiter authRateLimiter;

    /**
     * 刷新JWT Token
     * 使用refresh token获取新的access token和refresh token
     */
    @PostMapping("/refresh")
    @Operation(
        summary = "刷新JWT Token",
        description = "使用HttpOnly refresh Cookie轮换当前token family",
        tags = { "Token Management" }
    )
    @ApiResponses({
        @ApiResponse(
            responseCode = "200",
            description = "Token刷新成功",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"message\": \"Token refreshed successfully\", \"accessToken\": \"...\", \"accessTokenExpiresIn\": 3600, \"refreshTokenExpiresIn\": 604800, \"tokenType\": \"Bearer\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Token刷新失败",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    example = "{\"error\": \"Token refresh failed\", \"details\": \"Invalid refresh token\"}"
                )
            )
        )
    })
    public ResponseEntity<?> refreshToken(
            @Parameter(
                name = "refreshToken",
                description = "Refresh token（从cookie中获取）",
                required = true,
                in = io.swagger.v3.oas.annotations.enums.ParameterIn.COOKIE
            )
            HttpServletRequest request,
            HttpServletResponse response) {

        try {
            log.info("Token refresh request received");

            String refreshTokenCookie = credentialResolver
                    .resolveRefreshToken(request)
                    .orElse(null);
            if (refreshTokenCookie == null) {
                log.warn("No refresh token found in cookies");
                return ResponseEntity.status(401).body(
                    Map.of("error", "Refresh token not found")
                );
            }

            authRateLimiter.requireAllowed(
                    org.dddml.uniauth.service.AuthRateLimiter.Policy.REFRESH,
                    request.getRemoteAddr(),
                    refreshTokenCookie
            );

            // 刷新token
            TokenRefreshService.TokenPair tokenPair = tokenRefreshService.refreshUserTokens(refreshTokenCookie);
            log.info("Tokens refreshed successfully");

            // 设置新的Cookies
            authCookieService.writeTokenCookies(
                    response,
                    tokenPair.getAccessToken(),
                    tokenPair.getRefreshToken()
            );

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("message", "Token refreshed successfully");
            if (transportProperties.isExposeAccessToken()) {
                body.put("accessToken", tokenPair.getAccessToken());
            }
            body.put("accessTokenExpiresIn", 3600);
            body.put("refreshTokenExpiresIn", 604800);
            body.put("tokenType", "Bearer");
            return ResponseEntity.ok(body);


        } catch (org.dddml.uniauth.service.AuthRateLimitExceededException
                 | org.dddml.uniauth.service.AuthRateLimiterUnavailableException
                 exception) {
            throw exception;
        } catch (TokenRejectedException | JwtException exception) {
            log.warn("Token refresh rejected");
            return ResponseEntity.status(401).body(
                Map.of("error", "Token refresh failed")
            );
        } catch (DataAccessException exception) {
            log.error("Token refresh persistence failed");
            return ResponseEntity.status(503).body(
                Map.of("error", "TOKEN_REFRESH_UNAVAILABLE")
            );
        } catch (Exception exception) {
            log.error("Token refresh failed");
            return ResponseEntity.status(503).body(
                Map.of("error", "TOKEN_REFRESH_UNAVAILABLE")
            );
        }
    }

}
