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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO web3_nonces (
                id, wallet_address, nonce, message, expires_at, created_at
            ) VALUES (
                :id, :walletAddress, :nonce, :message, :expiresAt, CURRENT_TIMESTAMP
            )
            ON CONFLICT (wallet_address) DO UPDATE
            SET nonce = EXCLUDED.nonce,
                message = EXCLUDED.message,
                expires_at = EXCLUDED.expires_at,
                created_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int upsertNonce(
            String id,
            String walletAddress,
            String nonce,
            String message,
            Instant expiresAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            DELETE FROM Web3Nonce n
            WHERE n.walletAddress = :walletAddress
              AND n.nonce = :nonce
              AND n.message = :message
              AND n.expiresAt > :now
            """)
    int consumeNonce(
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
