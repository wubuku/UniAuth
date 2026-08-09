package org.dddml.uniauth.config;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.service.TokenSessionTransactionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class TokenSessionSchedulingConfig {

    private final TokenSessionTransactionService transactionService;

    @Scheduled(
        fixedDelayString =
                "${app.auth.session.cleanup-delay-ms:3600000}"
    )
    public void cleanupExpiredFamilies() {
        transactionService.cleanupExpired(Duration.ofDays(7));
    }
}
