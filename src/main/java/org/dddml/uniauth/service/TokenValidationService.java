package org.dddml.uniauth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.entity.TokenBlacklistEntity;
import org.dddml.uniauth.entity.TokenFamilyEntity;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.TokenBlacklistRepository;
import org.dddml.uniauth.repository.TokenFamilyRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenValidationService {

    private static final String ACCESS_TYPE = "access";
    private static final String REFRESH_TYPE = "refresh";
    private static final String RS256 = "RS256";

    private final JwtTokenService jwtTokenService;
    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final TokenFamilyRepository tokenFamilyRepository;
    private final UserRepository userRepository;

    private JwtDecoder signedAccessTokenDecoder;
    private JwtDecoder activeAccessTokenDecoder;

    @PostConstruct
    void initialize() {
        signedAccessTokenDecoder = jwtTokenService.jwtDecoder();
        activeAccessTokenDecoder = jwtTokenService.jwtDecoder(
                this::validateActiveAccessToken
        );
    }

    public JwtDecoder accessTokenDecoder() {
        return activeAccessTokenDecoder;
    }

    public Jwt decodeAccessToken(String tokenValue) {
        return activeAccessTokenDecoder.decode(tokenValue);
    }

    public String getUserIdFromAccessToken(String tokenValue) {
        return toValidatedAccessToken(decodeAccessToken(tokenValue)).userId();
    }

    public ValidatedToken decodeRefreshTokenForRotation(String tokenValue) {
        return decodeSignedRefreshToken(tokenValue);
    }

    public ValidatedToken decodeRefreshToken(String tokenValue) {
        ValidatedToken token = decodeSignedRefreshToken(tokenValue);
        requireActive(token, true);
        return token;
    }

    public Optional<ValidatedToken> accessTokenForRevocation(
            String tokenValue) {
        try {
            return Optional.of(toValidatedAccessToken(
                    decodeSignedAccessToken(tokenValue)
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Optional<ValidatedToken> refreshTokenForRevocation(
            String tokenValue) {
        try {
            return Optional.of(decodeSignedRefreshToken(tokenValue));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public IntrospectedToken introspect(String tokenValue) {
        Jws<Claims> parsed = jwtTokenService.parseSignedToken(tokenValue);
        String type = parsed.getBody().get("type", String.class);
        if (ACCESS_TYPE.equals(type)) {
            Jwt jwt = decodeAccessToken(tokenValue);
            ValidatedToken token = toValidatedAccessToken(jwt);
            return new IntrospectedToken(
                    token.userId(),
                    normalizedAudience(jwt.getAudience()),
                    jwt.getIssuedAt(),
                    jwt.getExpiresAt(),
                    token.familyId(),
                    token.generation(),
                    token.securityVersion(),
                    token.authTime(),
                    ACCESS_TYPE
            );
        }
        if (REFRESH_TYPE.equals(type)) {
            ValidatedToken token = decodeRefreshToken(tokenValue);
            return new IntrospectedToken(
                    token.userId(),
                    null,
                    token.issuedAt(),
                    token.expiresAt(),
                    token.familyId(),
                    token.generation(),
                    token.securityVersion(),
                    token.authTime(),
                    REFRESH_TYPE
            );
        }
        throw new JwtException("Unsupported token type");
    }

    private Jwt decodeSignedAccessToken(String tokenValue) {
        return signedAccessTokenDecoder.decode(tokenValue);
    }

    private ValidatedToken toValidatedAccessToken(Jwt jwt) {
        return validatedToken(
                jwt.getId(),
                TokenBlacklistEntity.TokenType.ACCESS,
                jwt.getSubject(),
                jwt.getClaimAsString("userId"),
                jwt.getClaimAsString("username"),
                jwt.getClaimAsString("sid"),
                numberClaim(jwt.getClaims(), "generation"),
                numberClaim(jwt.getClaims(), "ver"),
                numberClaim(jwt.getClaims(), "auth_time"),
                jwt.getIssuedAt(),
                jwt.getExpiresAt()
        );
    }

    private ValidatedToken decodeSignedRefreshToken(String tokenValue) {
        Jws<Claims> parsed = jwtTokenService.parseSignedToken(tokenValue);
        requireHeader(parsed.getHeader());
        Claims claims = parsed.getBody();
        if (!REFRESH_TYPE.equals(claims.get("type", String.class))) {
            throw new JwtException("Only refresh tokens are accepted");
        }
        if (!jwtTokenService.getToken().getIssuer().equals(
                claims.getIssuer()
        )) {
            throw new JwtException("Refresh token issuer is invalid");
        }
        return validatedToken(
                claims.getId(),
                TokenBlacklistEntity.TokenType.REFRESH,
                claims.getSubject(),
                claims.get("userId", String.class),
                claims.get("username", String.class),
                claims.get("sid", String.class),
                numberClaim(claims, "generation"),
                numberClaim(claims, "ver"),
                numberClaim(claims, "auth_time"),
                claims.getIssuedAt() != null
                        ? claims.getIssuedAt().toInstant()
                        : null,
                claims.getExpiration() != null
                        ? claims.getExpiration().toInstant()
                        : null
        );
    }

    private ValidatedToken validatedToken(
            String jti,
            TokenBlacklistEntity.TokenType tokenType,
            String subject,
            String userId,
            String username,
            String familyId,
            long generation,
            long securityVersion,
            long authTimeEpochSeconds,
            Instant issuedAt,
            Instant expiresAt) {
        if (jti == null || jti.isBlank()) {
            throw new JwtException("Token jti is missing");
        }
        if (subject == null || subject.isBlank()
                || userId == null || userId.isBlank()
                || !subject.equals(userId)) {
            throw new JwtException("Token user identity is invalid");
        }
        if (username == null || username.isBlank()) {
            throw new JwtException("Token username is missing");
        }
        requireUuid(familyId);
        if (generation < 0 || securityVersion < 0 || authTimeEpochSeconds < 0) {
            throw new JwtException("Token session claims are invalid");
        }
        if (issuedAt == null
                || expiresAt == null
                || !expiresAt.isAfter(issuedAt)) {
            throw new JwtException("Token timestamps are invalid");
        }
        return new ValidatedToken(
                jti,
                tokenType,
                userId,
                username,
                familyId,
                generation,
                securityVersion,
                authTimeEpochSeconds == 0
                        ? null
                        : Instant.ofEpochSecond(authTimeEpochSeconds),
                issuedAt,
                expiresAt
        );
    }

    private void requireHeader(Map<String, Object> headers) {
        if (!RS256.equals(headers.get("alg"))) {
            throw new JwtException("Token algorithm is invalid");
        }
        Object kid = headers.get("kid");
        if (!(kid instanceof String kidValue)
                || !jwtTokenService.getToken().getKid().equals(kidValue)) {
            throw new JwtException("Token kid is invalid");
        }
    }

    private void requireActive(
            ValidatedToken token,
            boolean exactGeneration) {
        if (tokenBlacklistRepository.existsByJti(token.jti())) {
            throw new JwtException("Token has been revoked");
        }
        UserEntity user = userRepository.findById(token.userId())
                .orElseThrow(() -> new JwtException(
                        "Token user does not exist"
                ));
        TokenFamilyEntity family = tokenFamilyRepository
                .findById(token.familyId())
                .orElseThrow(() -> new JwtException(
                        "Token family does not exist"
                ));
        Instant now = Instant.now();
        boolean generationValid = exactGeneration
                ? family.getCurrentGeneration() == token.generation()
                : family.getCurrentGeneration() >= token.generation();
        if (!user.isEnabled()
                || !user.getUsername().equals(token.username())
                || user.getTokenSecurityVersion() != token.securityVersion()
                || !family.isActiveAt(now)
                || !family.getUserId().equals(token.userId())
                || family.getSecurityVersion() != token.securityVersion()
                || !generationValid) {
            throw new JwtException("Token session is inactive");
        }
    }

    private OAuth2TokenValidatorResult validateActiveAccessToken(Jwt jwt) {
        try {
            ValidatedToken token = toValidatedAccessToken(jwt);
            requireActive(token, false);
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            return invalidToken("Token session is inactive");
        }
    }

    private OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                description,
                null
        ));
    }

    private Object normalizedAudience(List<String> audience) {
        if (audience == null || audience.isEmpty()) {
            return null;
        }
        return audience.size() == 1 ? audience.get(0) : audience;
    }

    private long numberClaim(Map<String, ?> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) {
            throw new JwtException("Token " + name + " claim is invalid");
        }
        return number.longValue();
    }

    private void requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new JwtException("Token family identifier is missing");
        }
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new JwtException("Token family identifier is invalid");
        }
    }

    public record ValidatedToken(
            String jti,
            TokenBlacklistEntity.TokenType tokenType,
            String userId,
            String username,
            String familyId,
            long generation,
            long securityVersion,
            Instant authTime,
            Instant issuedAt,
            Instant expiresAt) {
    }

    public record IntrospectedToken(
            String subject,
            Object audience,
            Instant issuedAt,
            Instant expiresAt,
            String familyId,
            long generation,
            long securityVersion,
            Instant authTime,
            String type) {
    }
}
