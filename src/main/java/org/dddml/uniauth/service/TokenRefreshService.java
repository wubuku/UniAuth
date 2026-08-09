package org.dddml.uniauth.service;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JWT Token刷新服务
 * 处理refresh token验证和新的access token生成
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TokenRefreshService {

    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final TokenValidationService tokenValidationService;
    private final TokenRevocationService tokenRevocationService;

    /**
     * 刷新用户的JWT Token
     *
     * @param refreshTokenValue refresh token字符串
     * @return 新的TokenPair
     */
    @Transactional
    public TokenPair refreshUserTokens(String refreshTokenValue) {
        TokenValidationService.ValidatedToken refreshToken =
                tokenValidationService.decodeRefreshToken(refreshTokenValue);
        UserEntity user = userRepository.findById(refreshToken.userId())
                .orElseThrow(() -> new TokenRejectedException("Token user does not exist"));
        if (!user.isEnabled()
                || user.getUsername() == null
                || !user.getUsername().equals(refreshToken.username())) {
            throw new TokenRejectedException("Token user state is invalid");
        }

        tokenRevocationService.consumeRefreshToken(refreshToken);

        String newAccessToken = jwtTokenService.generateAccessToken(
            user.getUsername(), user.getEmail(), user.getId(), user.getAuthorities()
        );
        String newRefreshToken = jwtTokenService.generateRefreshToken(
            user.getUsername(), user.getId()
        );

        log.info("Token refresh completed");
        return new TokenPair(newAccessToken, newRefreshToken);
    }

    /**
     * Token对数据传输对象
     */
    public static class TokenPair {
        private final String accessToken;
        private final String refreshToken;

        public TokenPair(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }
    }
}
