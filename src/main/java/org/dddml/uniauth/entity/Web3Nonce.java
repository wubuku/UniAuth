package org.dddml.uniauth.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "web3_nonces")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Web3Nonce {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "wallet_address", nullable = false, unique = true, length = 255)
    private String walletAddress;

    @Column(name = "nonce", nullable = false, length = 100)
    private String nonce;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null || id.trim().isEmpty()) {
            id = UUID.randomUUID().toString();
        }
    }
}
