package org.dddml.uniauth.service;

import org.dddml.uniauth.entity.Web3Nonce;
import org.dddml.uniauth.repository.Web3NonceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Web3NonceService {

    private final Web3NonceRepository web3NonceRepository;

    @Transactional
    public void saveNonce(
            String walletAddress,
            String nonce,
            String message,
            Instant expiresAt
    ) {
        String normalizedAddress = walletAddress.toLowerCase();

        web3NonceRepository.upsertNonce(
                UUID.randomUUID().toString(),
                normalizedAddress,
                nonce,
                message,
                expiresAt
        );
        log.debug("Web3 nonce persisted");
    }

    @Transactional
    public String getNonce(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        Optional<Web3Nonce> existingNonce = web3NonceRepository.findByWalletAddress(normalizedAddress);

        if (existingNonce.isEmpty()) {
            log.debug("No active Web3 nonce found");
            return null;
        }

        Web3Nonce web3Nonce = existingNonce.get();
        Instant now = Instant.now();

        if (web3Nonce.getExpiresAt().isBefore(now)) {
            log.debug("Web3 nonce expired");
            web3NonceRepository.deleteByWalletAddressAndExpiresAtLessThanEqual(
                    normalizedAddress,
                    now
            );
            return null;
        }

        return web3Nonce.getNonce();
    }

    @Transactional
    public boolean consumeNonce(String walletAddress, String nonce, String message) {
        String normalizedAddress = walletAddress.toLowerCase();
        Instant now = Instant.now();
        int consumed = web3NonceRepository.consumeNonce(
                normalizedAddress,
                nonce,
                message,
                now
        );
        if (consumed == 0) {
            web3NonceRepository.deleteByWalletAddressAndExpiresAtLessThanEqual(
                    normalizedAddress,
                    now
            );
        }
        if (consumed == 1) {
            log.debug("Web3 nonce consumed");
        }
        return consumed == 1;
    }
}
