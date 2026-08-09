package org.dddml.uniauth.dto;

import java.time.Instant;

public record LoginMethodDto(
        String id,
        String authProvider,
        boolean isPrimary,
        boolean isVerified,
        Instant linkedAt,
        Instant lastUsedAt,
        String providerEmail,
        String providerUsername,
        String localUsername) {
}
