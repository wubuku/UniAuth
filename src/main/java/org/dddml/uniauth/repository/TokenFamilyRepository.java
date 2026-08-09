package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.TokenFamilyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface TokenFamilyRepository
        extends JpaRepository<TokenFamilyEntity, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE token_families
                SET current_generation = :nextGeneration,
                    updated_at = :now
                WHERE id = :familyId
                  AND user_id = :userId
                  AND security_version = :securityVersion
                  AND current_generation = :expectedGeneration
                  AND revoked_at IS NULL
                  AND expires_at > :now
                """,
        nativeQuery = true
    )
    int rotate(
            @Param("familyId") String familyId,
            @Param("userId") String userId,
            @Param("securityVersion") long securityVersion,
            @Param("expectedGeneration") long expectedGeneration,
            @Param("nextGeneration") long nextGeneration,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE token_families
                SET revoked_at = :now,
                    revoke_reason = :reason,
                    updated_at = :now
                WHERE id = :familyId
                  AND revoked_at IS NULL
                """,
        nativeQuery = true
    )
    int revokeIfActive(
            @Param("familyId") String familyId,
            @Param("reason") String reason,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE token_families
                SET revoked_at = :now,
                    revoke_reason = :reason,
                    updated_at = :now
                WHERE user_id = :userId
                  AND revoked_at IS NULL
                """,
        nativeQuery = true
    )
    int revokeAllActiveForUser(
            @Param("userId") String userId,
            @Param("reason") String reason,
            @Param("now") Instant now
    );

    @Modifying
    @Query(
        value = """
                DELETE FROM token_families
                WHERE expires_at < :cutoff
                """,
        nativeQuery = true
    )
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
