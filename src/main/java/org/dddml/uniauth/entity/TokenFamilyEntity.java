package org.dddml.uniauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "token_families", indexes = {
        @Index(
                name = "idx_token_families_user_active",
                columnList = "user_id, expires_at"
        ),
        @Index(
                name = "idx_token_families_expires_at",
                columnList = "expires_at"
        )
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenFamilyEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "security_version", nullable = false)
    private long securityVersion;

    @Column(name = "current_generation", nullable = false)
    private long currentGeneration;

    @Column(name = "auth_time")
    private Instant authTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 64)
    private String revokeReason;

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
