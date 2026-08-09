package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    public static final String REASON_REFRESH_ROTATED = "REFRESH_ROTATED";
    public static final String REASON_LOGOUT =
            TokenSessionTransactionService.REASON_LOGOUT;

    private final TokenSessionTransactionService transactionService;

    public void revokeTokens(
            Collection<TokenValidationService.ValidatedToken> tokens,
            String reason) {
        Set<String> families = new HashSet<>();
        for (TokenValidationService.ValidatedToken token : tokens) {
            families.add(token.familyId());
        }
        for (String familyId : families) {
            transactionService.revokeFamily(familyId, reason);
        }
    }
}
