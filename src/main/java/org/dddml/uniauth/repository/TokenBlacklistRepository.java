package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.TokenBlacklistEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Token黑名单Repository接口
 */
@Repository
public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklistEntity, String> {
    boolean existsByJti(String jti);
    Optional<TokenBlacklistEntity> findByJti(String jti);

    @Modifying
    @Query(
        value = """
                INSERT INTO token_blacklist (
                    id,
                    jti,
                    token_type,
                    user_id,
                    expires_at,
                    blacklisted_at,
                    reason
                ) VALUES (
                    :id,
                    :jti,
                    :tokenType,
                    :userId,
                    :expiresAt,
                    CURRENT_TIMESTAMP,
                    :reason
                )
                ON CONFLICT (jti) DO NOTHING
                """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("id") String id,
        @Param("jti") String jti,
        @Param("tokenType") String tokenType,
        @Param("userId") String userId,
        @Param("expiresAt") LocalDateTime expiresAt,
        @Param("reason") String reason
    );
}
