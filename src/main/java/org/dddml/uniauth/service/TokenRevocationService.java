package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.repository.TokenBlacklistRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    public static final String REASON_REFRESH_ROTATED = "REFRESH_ROTATED";
    public static final String REASON_LOGOUT = "LOGOUT";

    private final TokenBlacklistRepository tokenBlacklistRepository;

    @Transactional
    public void consumeRefreshToken(TokenValidationService.ValidatedToken token) {
        if (token.tokenType()
                != org.dddml.uniauth.entity.TokenBlacklistEntity.TokenType.REFRESH) {
            throw new TokenRejectedException("A refresh token is required");
        }
        if (insertIfAbsent(token, REASON_REFRESH_ROTATED) != 1) {
            throw new TokenRejectedException("Refresh token has already been used");
        }
    }

    @Transactional
    public void revokeTokens(
            Collection<TokenValidationService.ValidatedToken> tokens,
            String reason) {
        for (TokenValidationService.ValidatedToken token : tokens) {
            insertIfAbsent(token, reason);
        }
    }

    private int insertIfAbsent(
            TokenValidationService.ValidatedToken token,
            String reason) {
        return tokenBlacklistRepository.insertIfAbsent(
                UUID.randomUUID().toString(),
                token.jti(),
                token.tokenType().name(),
                token.userId(),
                LocalDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC),
                reason
        );
    }
}
