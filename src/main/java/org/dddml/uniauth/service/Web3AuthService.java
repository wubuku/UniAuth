package org.dddml.uniauth.service;

import org.dddml.uniauth.dto.web3.Web3NonceResponse;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.entity.UserLoginMethod.AuthProvider;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.util.Web3SignatureUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Web3AuthService {

    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final JwtTokenService jwtTokenService;

    @Value("${app.web3.nonce-expiration-seconds:300}")
    private long nonceExpirationSeconds;

    @Value("${app.web3.domain:localhost}")
    private String domain;

    private static final String NONCE_PREFIX = "web3:nonce:";

    public Web3NonceResponse generateNonce(String walletAddress) {
        if (!Web3SignatureUtils.isValidAddress(walletAddress)) {
            throw new IllegalArgumentException("Invalid wallet address format");
        }

        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = buildSiweMessage(normalizedAddress, nonce);

        saveNonce(normalizedAddress, nonce);

        log.info("Generated nonce for wallet: {}", normalizedAddress);

        return new Web3NonceResponse(nonce, message, nonceExpirationSeconds);
    }

    private void saveNonce(String walletAddress, String nonce) {
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, walletAddress.toLowerCase());

        if (existingMethod.isPresent()) {
            UserLoginMethod method = existingMethod.get();
            method.setProviderEmail(nonce);
            loginMethodRepository.save(method);
        }
    }

    private String getNonceFromStorage(String walletAddress) {
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, walletAddress.toLowerCase());

        return existingMethod.map(UserLoginMethod::getProviderEmail).orElse(null);
    }

    private void clearNonce(String walletAddress) {
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, walletAddress.toLowerCase());

        if (existingMethod.isPresent()) {
            UserLoginMethod method = existingMethod.get();
            method.setProviderEmail(null);
            loginMethodRepository.save(method);
        }
    }

    private String buildSiweMessage(String walletAddress, String nonce) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(nonceExpirationSeconds);

        return String.format(
                "%s wants you to sign in with your Ethereum account:\n" +
                "%s\n\n" +
                "By signing, you agree to authenticate with your wallet.\n\n" +
                "URI: https://%s\n" +
                "Version: 1\n" +
                "Chain ID: 1\n" +
                "Nonce: %s\n" +
                "Issued At: %s\n" +
                "Expiration Time: %s",
                domain,
                walletAddress,
                domain,
                nonce,
                now.toString(),
                expiry.toString()
        );
    }

    public boolean verifySignature(String walletAddress, String message, String signature, String nonce) {
        try {
            String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
            String storedNonce = getNonceFromStorage(normalizedAddress);

            if (storedNonce == null || !storedNonce.equals(nonce)) {
                log.error("Nonce mismatch or expired for wallet: {}", normalizedAddress);
                return false;
            }

            boolean isValid = Web3SignatureUtils.verifySignature(message, signature, normalizedAddress);

            if (isValid) {
                clearNonce(normalizedAddress);
                log.info("Signature verification successful for wallet: {}", normalizedAddress);
            } else {
                log.error("Signature verification failed for wallet: {}", normalizedAddress);
            }

            return isValid;
        } catch (Exception e) {
            log.error("Error during signature verification", e);
            return false;
        }
    }

    @Transactional
    public UserEntity findOrCreateUser(String walletAddress) {
        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);

        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress);

        if (existingMethod.isPresent()) {
            UserEntity user = existingMethod.get().getUser();
            user.setLastLoginAt(LocalDateTime.now());
            return userRepository.save(user);
        }

        String username = "web3_" + normalizedAddress.substring(2, 8) + "_" + System.currentTimeMillis();
        String email = normalizedAddress + "@web3.local";

        UserEntity user = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .username(username)
                .email(email)
                .displayName(normalizedAddress)
                .enabled(true)
                .emailVerified(true)
                .build();

        UserEntity savedUser = userRepository.save(user);

        UserLoginMethod loginMethod = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(savedUser)
                .authProvider(AuthProvider.WEB3)
                .providerUserId(normalizedAddress)
                .isPrimary(true)
                .isVerified(true)
                .build();

        loginMethodRepository.save(loginMethod);

        log.info("Created new Web3 user: {} with wallet: {}", savedUser.getId(), normalizedAddress);

        return savedUser;
    }

    public Optional<UserEntity> findUserByWallet(String walletAddress) {
        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);

        return loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress)
                .map(UserLoginMethod::getUser);
    }

    public boolean isWalletBound(String walletAddress) {
        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
        return loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress)
                .isPresent();
    }

    @Transactional
    public void bindWalletToUser(String userId, String walletAddress) {
        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);

        if (isWalletBound(walletAddress)) {
            throw new IllegalStateException("Wallet is already bound to another account");
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.hasLoginMethod(AuthProvider.WEB3)) {
            throw new IllegalStateException("User already has a Web3 wallet bound");
        }

        UserLoginMethod loginMethod = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(AuthProvider.WEB3)
                .providerUserId(normalizedAddress)
                .isPrimary(false)
                .isVerified(true)
                .build();

        loginMethodRepository.save(loginMethod);

        log.info("Bound wallet {} to user {}", normalizedAddress, userId);
    }
}
