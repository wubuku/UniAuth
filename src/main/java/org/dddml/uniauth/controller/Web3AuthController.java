package org.dddml.uniauth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.dto.ErrorResponse;
import org.dddml.uniauth.dto.web3.Web3AuthResponse;
import org.dddml.uniauth.dto.web3.Web3LoginRequest;
import org.dddml.uniauth.dto.web3.Web3NonceResponse;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.service.JwtTokenService;
import org.dddml.uniauth.service.Web3AuthService;
import org.dddml.uniauth.util.Web3SignatureUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/auth/web3")
@RequiredArgsConstructor
@Tag(name = "Web3 Authentication", description = "Web3 wallet authentication endpoints")
public class Web3AuthController {

    private final Web3AuthService web3AuthService;
    private final JwtTokenService jwtTokenService;

    @GetMapping("/nonce/{walletAddress}")
    @Operation(summary = "Get nonce for wallet authentication",
               description = "Generates a nonce and SIWE message for the given wallet address")
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Nonce generated successfully",
                    content = @Content(schema = @Schema(implementation = Web3NonceResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid wallet address",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<?> getNonce(@PathVariable String walletAddress) {
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

            Web3NonceResponse response = web3AuthService.generateNonce(walletAddress);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error generating nonce", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Failed to generate nonce")
                            .detail(e.getMessage())
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
    public ResponseEntity<?> verifyAndLogin(@Valid @RequestBody Web3LoginRequest request) {
        try {
            String normalizedAddress = Web3SignatureUtils.normalizeAddress(request.getWalletAddress());

            boolean isValid = web3AuthService.verifySignature(
                    normalizedAddress,
                    request.getMessage(),
                    request.getSignature(),
                    request.getNonce()
            );

            if (!isValid) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ErrorResponse.builder()
                                .status(401)
                                .errorCode("INVALID_SIGNATURE")
                                .message("Signature verification failed")
                                .timestamp(LocalDateTime.now())
                                .build());
            }

            UserEntity user = web3AuthService.findOrCreateUser(normalizedAddress);

            String accessToken = jwtTokenService.generateAccessToken(
                    user.getUsername(),
                    user.getEmail(),
                    user.getId(),
                    user.getAuthorities()
            );

            String refreshToken = jwtTokenService.generateRefreshToken(user.getUsername(), user.getId());

            long expiresIn = jwtTokenService.getExpires().getAccessToken() / 1000;

            Web3AuthResponse response = Web3AuthResponse.builder()
                    .accessToken(accessToken)
                    .refreshToken(refreshToken)
                    .tokenType("Bearer")
                    .expiresIn(expiresIn)
                    .walletAddress(normalizedAddress)
                    .userId(user.getId())
                    .isNewUser(!web3AuthService.isWalletBound(normalizedAddress))
                    .build();

            log.info("Web3 login successful for wallet: {}, userId: {}", normalizedAddress, user.getId());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error during Web3 authentication", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Authentication failed")
                            .detail(e.getMessage())
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
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody Web3LoginRequest request) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ErrorResponse.builder()
                                .status(401)
                                .errorCode("MISSING_TOKEN")
                                .message("Authorization header required")
                                .timestamp(LocalDateTime.now())
                                .build());
            }

            String token = authHeader.substring(7);
            String userId = jwtTokenService.getUserIdFromToken(token);

            String normalizedAddress = Web3SignatureUtils.normalizeAddress(request.getWalletAddress());

            boolean isValid = web3AuthService.verifySignature(
                    normalizedAddress,
                    request.getMessage(),
                    request.getSignature(),
                    request.getNonce()
            );

            if (!isValid) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ErrorResponse.builder()
                                .status(401)
                                .errorCode("INVALID_SIGNATURE")
                                .message("Signature verification failed")
                                .timestamp(LocalDateTime.now())
                                .build());
            }

            web3AuthService.bindWalletToUser(userId, normalizedAddress);

            log.info("Wallet {} bound to user {}", normalizedAddress, userId);

            return ResponseEntity.ok()
                    .body(ErrorResponse.builder()
                            .status(200)
                            .errorCode("SUCCESS")
                            .message("Wallet bound successfully")
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder()
                            .status(400)
                            .errorCode("BINDING_FAILED")
                            .message(e.getMessage())
                            .timestamp(LocalDateTime.now())
                            .build());
        } catch (Exception e) {
            log.error("Error binding wallet", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Failed to bind wallet")
                            .detail(e.getMessage())
                            .timestamp(LocalDateTime.now())
                            .build());
        }
    }

    @GetMapping("/status/{walletAddress}")
    @Operation(summary = "Check wallet binding status",
               description = "Checks if a wallet address is already bound to an account")
    public ResponseEntity<?> checkWalletStatus(@PathVariable String walletAddress) {
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

            String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
            boolean isBound = web3AuthService.isWalletBound(normalizedAddress);

            return ResponseEntity.ok()
                    .body(new WalletStatusResponse(normalizedAddress, isBound));
        } catch (Exception e) {
            log.error("Error checking wallet status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.builder()
                            .status(500)
                            .errorCode("INTERNAL_ERROR")
                            .message("Failed to check wallet status")
                            .detail(e.getMessage())
                            .timestamp(LocalDateTime.now())
                            .build());
        }
    }

    public record WalletStatusResponse(String walletAddress, boolean isBound) {}
}
