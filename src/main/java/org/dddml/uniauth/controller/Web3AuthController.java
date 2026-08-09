package org.dddml.uniauth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.dto.ErrorResponse;
import org.dddml.uniauth.dto.web3.Web3AuthResponse;
import org.dddml.uniauth.dto.web3.Web3LoginRequest;
import org.dddml.uniauth.dto.web3.Web3NonceResponse;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.service.TokenIssuanceFacade;
import org.dddml.uniauth.service.TokenValidationService;
import org.dddml.uniauth.service.UserService;
import org.dddml.uniauth.service.AuthenticationCredentialResolver;
import org.dddml.uniauth.service.AuthRateLimitExceededException;
import org.dddml.uniauth.service.AuthRateLimiter;
import org.dddml.uniauth.service.AuthRateLimiterUnavailableException;
import org.dddml.uniauth.service.RecentAuthenticationRequiredException;
import org.dddml.uniauth.service.RecentAuthenticationService;
import org.dddml.uniauth.service.Web3AuthenticationRejectedException;
import org.dddml.uniauth.service.Web3BindingConflictException;
import org.dddml.uniauth.service.Web3ChallengeCapacityExceededException;
import org.dddml.uniauth.service.Web3AuthService;
import org.dddml.uniauth.util.Web3SignatureUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/api/auth/web3")
@RequiredArgsConstructor
@Tag(name = "Web3 Authentication", description = "Web3 wallet authentication endpoints")
public class Web3AuthController {

    private final Web3AuthService web3AuthService;
    private final TokenValidationService tokenValidationService;
    private final TokenIssuanceFacade tokenIssuanceFacade;
    private final UserService userService;
    private final AuthenticationCredentialResolver credentialResolver;
    private final RecentAuthenticationService recentAuthenticationService;
    private final AuthRateLimiter authRateLimiter;

    @GetMapping("/nonce/{walletAddress}")
    @Operation(summary = "Get nonce for wallet authentication",
               description = "Generates a nonce and SIWE message for the given wallet address")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Nonce generated successfully",
                    content = @Content(schema = @Schema(implementation = Web3NonceResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid wallet address",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> getNonce(
            @PathVariable String walletAddress,
            HttpServletRequest httpRequest) {
        try {
            if (!Web3SignatureUtils.isValidAddress(walletAddress)) {
                return ResponseEntity.badRequest()
                        .body(ErrorResponse.builder()
                                .status(400)
                                .errorCode("INVALID_ADDRESS")
                                .message("Invalid wallet address format")
                                .timestamp(LocalDateTime.now())
                                .build());
            }

            authRateLimiter.requireAllowed(
                    AuthRateLimiter.Policy.WEB3_CHALLENGE,
                    httpRequest.getRemoteAddr(),
                    walletAddress
            );
            Web3NonceResponse response = web3AuthService.generateNonce(
                    walletAddress,
                    httpRequest.getRemoteAddr()
            );
            return ResponseEntity.ok(response);
        } catch (AuthRateLimitExceededException
                 | AuthRateLimiterUnavailableException
                 | Web3ChallengeCapacityExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("Web3 nonce generation failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Failed to generate nonce")
                            .timestamp(LocalDateTime.now())
                            .build());
        }
    }
    
    @PostMapping("/verify")
    @Operation(summary = "Verify signature and authenticate",
               description = "Verifies the signature and returns JWT tokens on success")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Authentication successful",
                    content = @Content(schema = @Schema(implementation = Web3AuthResponse.class))),
        @ApiResponse(responseCode = "401", description = "Signature verification failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request parameters",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> verifyAndLogin(
            @Valid @RequestBody Web3LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        try {
            String normalizedAddress = Web3SignatureUtils.normalizeAddress(request.getWalletAddress());

            authRateLimiter.requireAllowed(
                    AuthRateLimiter.Policy.WEB3_VERIFY,
                    httpRequest.getRemoteAddr(),
                    normalizedAddress
            );
            Web3AuthService.AuthenticationResult result =
                    web3AuthService.authenticate(request);
            UserEntity user = result.user();

            Map<String, Object> responseBody = new LinkedHashMap<>(
                    tokenIssuanceFacade.issue(
                            userService.convertToDto(user),
                            httpRequest,
                            response,
                            "Web3 login successful",
                            Instant.now()
                    )
            );
            responseBody.put("walletAddress", normalizedAddress);
            responseBody.put("userId", user.getId());
            responseBody.put("isNewUser", result.newUser());

            log.info("Web3 login completed");

            return ResponseEntity.ok(responseBody);
        } catch (AuthRateLimitExceededException
                 | AuthRateLimiterUnavailableException e) {
            throw e;
        } catch (Web3AuthenticationRejectedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder()
                            .status(401)
                            .errorCode("INVALID_SIGNATURE")
                            .message("Signature verification failed")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (Web3BindingConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.builder()
                            .status(409)
                            .errorCode("AUTHENTICATION_CONFLICT")
                            .message("Authentication could not be completed")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (Exception e) {
            log.error("Web3 authentication failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Authentication failed")
                            .timestamp(LocalDateTime.now())
                            .build());
        }
    }

    @PostMapping("/bind")
    @Operation(summary = "Bind wallet to existing account",
               description = "Binds a Web3 wallet to an already authenticated user account")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Wallet bound successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request or wallet already bound"),
        @ApiResponse(responseCode = "401", description = "User not authenticated")
    })
    public ResponseEntity<?> bindWallet(
            HttpServletRequest httpRequest,
            @Valid @RequestBody Web3LoginRequest request) {
        try {
            var bearerToken = credentialResolver.resolveAccessToken(
                    httpRequest
            );
            if (bearerToken.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ErrorResponse.builder()
                                .status(401)
                                .errorCode("MISSING_TOKEN")
                                .message("Authorization header required")
                                .timestamp(LocalDateTime.now())
                                .build());
            }

            var token = tokenValidationService.validatedAccessToken(
                    bearerToken.orElseThrow()
            );
            recentAuthenticationService.requireRecent(token);

            String normalizedAddress = Web3SignatureUtils.normalizeAddress(request.getWalletAddress());
            authRateLimiter.requireAllowed(
                    AuthRateLimiter.Policy.LOGIN_METHOD_MUTATION,
                    httpRequest.getRemoteAddr(),
                    token.userId() + "|" + normalizedAddress
            );
            web3AuthService.bindWalletToUser(
                    token.userId(),
                    token.securityVersion(),
                    request
            );

            log.info("Web3 wallet binding completed");

            return ResponseEntity.ok()
                    .body(ErrorResponse.builder()
                            .status(200)
                            .errorCode("SUCCESS")
                            .message("Wallet bound successfully")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (JwtException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder()
                            .status(401)
                            .errorCode("INVALID_TOKEN")
                            .message("A valid access token is required")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (RecentAuthenticationRequiredException
                 | AuthRateLimitExceededException
                 | AuthRateLimiterUnavailableException e) {
            throw e;
        } catch (Web3AuthenticationRejectedException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ErrorResponse.builder()
                            .status(401)
                            .errorCode("INVALID_SIGNATURE")
                            .message("Signature verification failed")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (Web3BindingConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.builder()
                            .status(409)
                            .errorCode("BINDING_FAILED")
                            .message("Wallet could not be bound")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (Exception e) {
            log.error("Web3 wallet binding failed");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Failed to bind wallet")
                            .timestamp(LocalDateTime.now())
                            .build());
        }
    }

}
