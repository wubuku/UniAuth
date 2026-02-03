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
    public void saveNonce(String walletAddress, String nonce, long expirationSeconds) {
        String normalizedAddress = walletAddress.toLowerCase();
        Instant expiresAt = Instant.now().plusSeconds(expirationSeconds);

        log.info("SAVE NONCE - wallet: {}, nonce: {}, expiresAt: {}, now: {}, expirationSeconds: {}",
                normalizedAddress, nonce, expiresAt, Instant.now(), expirationSeconds);

        Optional<Web3Nonce> existingNonce = web3NonceRepository.findByWalletAddress(normalizedAddress);

        if (existingNonce.isPresent()) {
            Web3Nonce web3Nonce = existingNonce.get();
            web3Nonce.setNonce(nonce);
            web3Nonce.setExpiresAt(expiresAt);
            web3NonceRepository.save(web3Nonce);
        } else {
            Web3Nonce web3Nonce = Web3Nonce.builder()
                    .id(UUID.randomUUID().toString())
                    .walletAddress(normalizedAddress)
                    .nonce(nonce)
                    .expiresAt(expiresAt)
                    .build();
            web3NonceRepository.save(web3Nonce);
        }
        log.debug("Saved nonce for wallet: {}", normalizedAddress);
    }

    @Transactional(readOnly = true)
    public String getNonce(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        Optional<Web3Nonce> existingNonce = web3NonceRepository.findByWalletAddress(normalizedAddress);

        if (existingNonce.isEmpty()) {
            log.info("GET NONCE - no nonce found for wallet: {}", normalizedAddress);
            return null;
        }

        Web3Nonce web3Nonce = existingNonce.get();
        Instant now = Instant.now();
        log.info("GET NONCE - wallet: {}, nonce: {}, expiresAt: {}, now: {}, isExpired: {}",
                normalizedAddress, web3Nonce.getNonce(), web3Nonce.getExpiresAt(), now, web3Nonce.getExpiresAt().isBefore(now));

        if (web3Nonce.getExpiresAt().isBefore(now)) {
            log.debug("Nonce expired for wallet: {}", normalizedAddress);
            web3NonceRepository.delete(web3Nonce);
            return null;
        }

        return web3Nonce.getNonce();
    }

    @Transactional
    public void deleteNonce(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        web3NonceRepository.deleteByWalletAddress(normalizedAddress);
        log.debug("Deleted nonce for wallet: {}", normalizedAddress);
    }
}
