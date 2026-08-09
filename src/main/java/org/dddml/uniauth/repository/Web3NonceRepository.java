package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.Web3Nonce;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface Web3NonceRepository extends JpaRepository<Web3Nonce, String> {

    Optional<Web3Nonce> findByWalletAddress(String walletAddress);

    Optional<Web3Nonce> findByChallengeHandle(String challengeHandle);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM Web3Nonce n
            WHERE n.challengeHandle = :challengeHandle
              AND n.walletAddress = :walletAddress
              AND n.nonce = :nonce
              AND n.message = :message
              AND n.expiresAt > :now
            """)
    int consumeNonce(
            String challengeHandle,
            String walletAddress,
            String nonce,
            String message,
            Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    int deleteByWalletAddressAndExpiresAtLessThanEqual(
            String walletAddress,
            Instant now
    );
}
