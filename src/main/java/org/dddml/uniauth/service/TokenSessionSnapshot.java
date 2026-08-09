package org.dddml.uniauth.service;

import java.time.Instant;
import java.util.Set;

public record TokenSessionSnapshot(
        String familyId,
        long generation,
        long securityVersion,
        Instant authTime,
        Instant issuedAt,
        Instant familyExpiresAt,
        String userId,
        String username,
        String email,
        Set<String> authorities) {
}
