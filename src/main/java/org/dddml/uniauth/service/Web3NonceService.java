package org.dddml.uniauth.service;

import org.dddml.uniauth.entity.Web3Nonce;
import org.dddml.uniauth.repository.Web3NonceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Web3NonceService {

    private static final String GLOBAL_BUCKET = "global";

    private final Web3NonceRepository web3NonceRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuthRateLimiter authRateLimiter;

    @Value("${app.web3.max-active-global:1000}")
    private int maxActiveGlobal;

    @Value("${app.web3.max-active-source:10}")
    private int maxActiveSource;

    @Transactional
    public String saveNonce(
            String walletAddress,
            String nonce,
            String message,
            String trustedSource,
            Instant expiresAt
    ) {
        String normalizedAddress = walletAddress.toLowerCase();
        cleanupExpired();
        String sourceKey = authRateLimiter.protectedKey(
                "web3-challenge-source",
                trustedSource
        );
        reserveCounter(GLOBAL_BUCKET, maxActiveGlobal);
        reserveCounter("source:" + sourceKey, maxActiveSource);
        String handle = UUID.randomUUID().toString();
        try {
            web3NonceRepository.saveAndFlush(Web3Nonce.builder()
                    .id(UUID.randomUUID().toString())
                    .walletAddress(normalizedAddress)
                    .nonce(nonce)
                    .message(message)
                    .challengeHandle(handle)
                    .sourceKey(sourceKey)
                    .expiresAt(expiresAt)
                    .build());
            log.debug("Web3 nonce persisted");
            return handle;
        } catch (DataIntegrityViolationException exception) {
            throw new Web3ChallengeCapacityExceededException();
        }
    }

    @Transactional
    public String getNonce(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        Optional<Web3Nonce> existingNonce = web3NonceRepository.findByWalletAddress(normalizedAddress);

        if (existingNonce.isEmpty()) {
            log.debug("No active Web3 nonce found");
            return null;
        }

        Web3Nonce web3Nonce = existingNonce.get();
        Instant now = Instant.now();

        if (web3Nonce.getExpiresAt().isBefore(now)) {
            log.debug("Web3 nonce expired");
            cleanupExpired();
            return null;
        }

        return web3Nonce.getNonce();
    }

    @Transactional
    public boolean consumeNonce(
            String challengeHandle,
            String walletAddress,
            String nonce,
            String message) {
        String normalizedAddress = walletAddress.toLowerCase();
        Instant now = Instant.now();
        Optional<Web3Nonce> candidate =
                web3NonceRepository.findByChallengeHandle(challengeHandle);
        if (candidate.isEmpty()) {
            return false;
        }
        Web3Nonce stored = candidate.get();
        if (!stored.getWalletAddress().equals(normalizedAddress)
                || !stored.getNonce().equals(nonce)
                || !stored.getMessage().equals(message)
                || !stored.getExpiresAt().isAfter(now)) {
            if (!stored.getExpiresAt().isAfter(now)) {
                cleanupExpired();
            }
            return false;
        }
        int consumed = web3NonceRepository.consumeNonce(
                challengeHandle,
                normalizedAddress,
                nonce,
                message,
                now
        );
        if (consumed == 1) {
            releaseCounter(GLOBAL_BUCKET, 1);
            releaseCounter("source:" + stored.getSourceKey(), 1);
            log.debug("Web3 nonce consumed");
        }
        return consumed == 1;
    }

    @Transactional
    public int cleanupExpired() {
        List<String> sourceKeys = jdbcTemplate.query(
                """
                DELETE FROM web3_nonces
                WHERE expires_at <= ?
                RETURNING source_key
                """,
                (resultSet, rowNumber) -> resultSet.getString(1),
                Timestamp.from(Instant.now())
        );
        if (!sourceKeys.isEmpty()) {
            releaseCounter(GLOBAL_BUCKET, sourceKeys.size());
            sourceKeys.stream()
                    .distinct()
                    .forEach(sourceKey -> releaseCounter(
                            "source:" + sourceKey,
                            (int) sourceKeys.stream()
                                    .filter(sourceKey::equals)
                                    .count()
                    ));
        }
        return sourceKeys.size();
    }

    private void reserveCounter(String bucketKey, int limit) {
        List<Integer> reserved = jdbcTemplate.query(
                """
                INSERT INTO web3_challenge_counters (
                    bucket_key,
                    active_count,
                    updated_at
                )
                VALUES (?, 1, CURRENT_TIMESTAMP)
                ON CONFLICT (bucket_key) DO UPDATE
                SET active_count =
                        web3_challenge_counters.active_count + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE web3_challenge_counters.active_count < ?
                RETURNING active_count
                """,
                (resultSet, rowNumber) -> resultSet.getInt(1),
                bucketKey,
                limit
        );
        if (reserved.size() != 1) {
            throw new Web3ChallengeCapacityExceededException();
        }
    }

    private void releaseCounter(String bucketKey, int count) {
        jdbcTemplate.update(
                """
                UPDATE web3_challenge_counters
                SET active_count = greatest(0, active_count - ?),
                    updated_at = CURRENT_TIMESTAMP
                WHERE bucket_key = ?
                """,
                count,
                bucketKey
        );
        jdbcTemplate.update(
                """
                DELETE FROM web3_challenge_counters
                WHERE bucket_key = ?
                  AND active_count = 0
                """,
                bucketKey
        );
    }
}
