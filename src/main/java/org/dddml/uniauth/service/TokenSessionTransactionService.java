package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.entity.TokenFamilyEntity;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.TokenFamilyRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TokenSessionTransactionService {

    public static final String REASON_REFRESH_REPLAY = "REFRESH_REPLAY";
    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_SESSION_REPLACED = "SESSION_REPLACED";

    private final UserRepository userRepository;
    private final TokenFamilyRepository tokenFamilyRepository;
    private final JwtTokenService jwtTokenService;
    private final SecurityEventService securityEventService;

    @Transactional
    public TokenSessionSnapshot create(
            String userId,
            Instant authTime,
            String familyToReplace) {
        Instant now = Instant.now();
        UserEntity user = requireEnabledUser(userId);
        if (familyToReplace != null) {
            TokenFamilyEntity existing = tokenFamilyRepository
                    .findById(familyToReplace)
                    .orElseThrow(() -> new TokenRejectedException(
                            "Existing token family does not exist"
                    ));
            if (!existing.getUserId().equals(userId)) {
                throw new TokenRejectedException(
                        "A different user must be logged out first"
                );
            }
            tokenFamilyRepository.revokeIfActive(
                    existing.getId(),
                    REASON_SESSION_REPLACED,
                    now
            );
        }

        String familyId = UUID.randomUUID().toString();
        Instant familyExpiresAt = now.plusMillis(
                jwtTokenService.getExpires().getRefreshToken()
        );
        TokenFamilyEntity family = TokenFamilyEntity.builder()
                .id(familyId)
                .userId(user.getId())
                .securityVersion(user.getTokenSecurityVersion())
                .currentGeneration(0)
                .authTime(authTime)
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(familyExpiresAt)
                .build();
        tokenFamilyRepository.saveAndFlush(family);
        securityEventService.append(
                "TOKEN_FAMILY_CREATED",
                user.getId(),
                SecurityEventService.Outcome.SUCCESS,
                null
        );
        return snapshot(family, user, now);
    }

    @Transactional
    public RotationResult rotate(
            TokenValidationService.ValidatedToken token) {
        Instant now = Instant.now();
        UserEntity user = userRepository.findById(token.userId()).orElse(null);
        TokenFamilyEntity family = tokenFamilyRepository
                .findById(token.familyId())
                .orElse(null);
        if (!matchesSecurityState(token, user, family, now)) {
            return RotationResult.rejected();
        }
        if (family.getCurrentGeneration() > token.generation()) {
            return revokeReplay(token, now);
        }
        if (family.getCurrentGeneration() < token.generation()) {
            return RotationResult.rejected();
        }

        long nextGeneration = token.generation() + 1;
        if (tokenFamilyRepository.rotate(
                token.familyId(),
                token.userId(),
                token.securityVersion(),
                token.generation(),
                nextGeneration,
                now
        ) == 1) {
            family.setCurrentGeneration(nextGeneration);
            family.setUpdatedAt(now);
            securityEventService.append(
                    "TOKEN_FAMILY_ROTATED",
                    user.getId(),
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            return RotationResult.rotated(snapshot(family, user, now));
        }

        TokenFamilyEntity current = tokenFamilyRepository
                .findById(token.familyId())
                .orElse(null);
        if (current != null
                && current.isActiveAt(now)
                && current.getUserId().equals(token.userId())
                && current.getSecurityVersion() == token.securityVersion()
                && current.getCurrentGeneration() > token.generation()) {
            return revokeReplay(token, now);
        }
        return RotationResult.rejected();
    }

    @Transactional
    public boolean revokeFamily(String familyId, String reason) {
        TokenFamilyEntity family = tokenFamilyRepository
                .findById(familyId)
                .orElse(null);
        if (family == null) {
            return false;
        }
        int changed = tokenFamilyRepository.revokeIfActive(
                familyId,
                reason,
                Instant.now()
        );
        if (changed == 1) {
            securityEventService.append(
                    "TOKEN_FAMILY_REVOKED",
                    family.getUserId(),
                    SecurityEventService.Outcome.SUCCESS,
                    reason
            );
        }
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long incrementSecurityVersionAndRevoke(
            String userId,
            String reason) {
        long current = userRepository.findTokenSecurityVersion(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "User does not exist"
                ));
        if (userRepository.compareAndIncrementTokenSecurityVersion(
                userId,
                current
        ) != 1) {
            throw new LoginMethodConflictException(
                    "User security state was modified concurrently"
            );
        }
        tokenFamilyRepository.revokeAllActiveForUser(
                userId,
                reason,
                Instant.now()
        );
        securityEventService.append(
                "TOKEN_SECURITY_VERSION_INCREMENTED",
                userId,
                SecurityEventService.Outcome.SUCCESS,
                reason
        );
        return current + 1;
    }

    @Transactional
    public int cleanupExpired(Duration retention) {
        return tokenFamilyRepository.deleteExpiredBefore(
                Instant.now().minus(retention)
        );
    }

    private RotationResult revokeReplay(
            TokenValidationService.ValidatedToken token,
            Instant now) {
        tokenFamilyRepository.revokeIfActive(
                token.familyId(),
                REASON_REFRESH_REPLAY,
                now
        );
        securityEventService.append(
                "TOKEN_FAMILY_REPLAY_REVOKED",
                token.userId(),
                SecurityEventService.Outcome.DENIED,
                REASON_REFRESH_REPLAY
        );
        return RotationResult.replay();
    }

    private boolean matchesSecurityState(
            TokenValidationService.ValidatedToken token,
            UserEntity user,
            TokenFamilyEntity family,
            Instant now) {
        return user != null
                && user.isEnabled()
                && user.getUsername().equals(token.username())
                && user.getTokenSecurityVersion() == token.securityVersion()
                && family != null
                && family.isActiveAt(now)
                && family.getUserId().equals(token.userId())
                && family.getSecurityVersion() == token.securityVersion();
    }

    private UserEntity requireEnabledUser(String userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new TokenRejectedException(
                        "Token user does not exist"
                ));
        if (!user.isEnabled()
                || user.getUsername() == null
                || user.getUsername().isBlank()) {
            throw new TokenRejectedException("Token user state is invalid");
        }
        return user;
    }

    private TokenSessionSnapshot snapshot(
            TokenFamilyEntity family,
            UserEntity user,
            Instant issuedAt) {
        return new TokenSessionSnapshot(
                family.getId(),
                family.getCurrentGeneration(),
                family.getSecurityVersion(),
                family.getAuthTime(),
                issuedAt,
                family.getExpiresAt(),
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                new HashSet<>(user.getAuthorities())
        );
    }

    public record RotationResult(
            TokenSessionSnapshot snapshot,
            Status status) {

        static RotationResult rotated(TokenSessionSnapshot snapshot) {
            return new RotationResult(snapshot, Status.ROTATED);
        }

        static RotationResult replay() {
            return new RotationResult(null, Status.REPLAY);
        }

        static RotationResult rejected() {
            return new RotationResult(null, Status.REJECTED);
        }
    }

    public enum Status {
        ROTATED,
        REPLAY,
        REJECTED
    }
}
