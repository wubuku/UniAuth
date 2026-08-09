package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRefreshService {

    private final TokenValidationService tokenValidationService;
    private final TokenSessionTransactionService transactionService;
    private final TokenIssuanceFacade tokenIssuanceFacade;

    public TokenPair refreshUserTokens(String refreshTokenValue) {
        TokenValidationService.ValidatedToken refreshToken =
                tokenValidationService.decodeRefreshTokenForRotation(
                        refreshTokenValue
                );
        TokenSessionTransactionService.RotationResult result =
                transactionService.rotate(refreshToken);
        if (result.status()
                != TokenSessionTransactionService.Status.ROTATED) {
            throw new TokenRejectedException(
                    result.status()
                            == TokenSessionTransactionService.Status.REPLAY
                            ? "Refresh token replay detected"
                            : "Refresh token is inactive"
            );
        }

        TokenIssuanceFacade.TokenPair tokenPair =
                tokenIssuanceFacade.sign(result.snapshot());
        log.info("Token refresh completed");
        return new TokenPair(
                tokenPair.accessToken(),
                tokenPair.refreshToken()
        );
    }

    public record TokenPair(String accessToken, String refreshToken) {

        public String getAccessToken() {
            return accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }
    }
}
