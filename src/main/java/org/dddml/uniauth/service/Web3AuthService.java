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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Web3AuthService {

    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final Web3NonceService web3NonceService;
    private final JwtTokenService jwtTokenService;
    
    @Value("${app.web3.domain:localhost}")
    private String domain;
    
    @Value("${app.web3.nonce-expiration-seconds:300}")
    private long nonceExpirationSeconds;

    @Value("${app.web3.message-format}")
    private String messageFormat;

    public Web3NonceResponse generateNonce(String walletAddress) {
        if (!Web3SignatureUtils.isValidAddress(walletAddress)) {
            throw new IllegalArgumentException("Invalid wallet address format");
        }

        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String message = buildSiweMessage(normalizedAddress, nonce);
        
        web3NonceService.saveNonce(normalizedAddress, nonce, nonceExpirationSeconds);

        log.info("Generated nonce for wallet: {}", normalizedAddress);

        return new Web3NonceResponse(nonce, message, nonceExpirationSeconds);
    }

    private String buildSiweMessage(String walletAddress, String nonce) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(nonceExpirationSeconds);
        String uri = "https://" + domain;

        // Use configured format or fallback to default if missing (though @Value should enforce it if not optional)
        String template = (messageFormat != null && !messageFormat.isBlank()) ? messageFormat :
                "%1$s wants you to sign in with your Ethereum account:\n" +
                "%2$s\n\n" +
                "By signing, you agree to authenticate with your wallet.\n\n" +
                "URI: %3$s\n" +
                "Version: 1\n" +
                "Chain ID: 1\n" +
                "Nonce: %4$s\n" +
                "Issued At: %5$s\n" +
                "Expiration Time: %6$s";

        return String.format(
                template,
                domain,
                walletAddress,
                uri,
                nonce,
                now.toString(),
                expiry.toString()
        );
    }

    @Transactional
    public boolean verifySignature(String walletAddress, String message, String signature, String nonce) {
        try {
            String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
            String storedNonce = web3NonceService.getNonce(normalizedAddress);

            if (storedNonce == null || !storedNonce.equals(nonce)) {
                log.error("Nonce mismatch or expired for wallet: {}", normalizedAddress);
                return false;
            }

            boolean isValid = Web3SignatureUtils.verifySignature(message, signature, normalizedAddress);

            if (isValid) {
                web3NonceService.deleteNonce(normalizedAddress);
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

    public UserEntity findOrCreateUser(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress);
        
        if (existingMethod.isPresent()) {
            UserLoginMethod method = existingMethod.get();
            UserEntity user = method.getUser();
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
            return user;
        }
        
        UserEntity newUser = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .username(normalizedAddress)
                .email(normalizedAddress + "@web3.local")
                .displayName("Web3 User")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .lastLoginAt(LocalDateTime.now())
                .build();
        userRepository.save(newUser);
        
        UserLoginMethod newMethod = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(newUser)
                .authProvider(AuthProvider.WEB3)
                .providerUserId(normalizedAddress)
                .isPrimary(true)
                .isVerified(true)
                .linkedAt(Instant.now())
                .build();
        loginMethodRepository.save(newMethod);
        
        log.info("Created new user via Web3 wallet: {}", normalizedAddress);
        
        return newUser;
    }

    @Transactional
    public boolean bindWalletToUser(String userId, String walletAddress) {
        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
        
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress);
        
        if (existingMethod.isPresent()) {
            log.error("Wallet already bound: {}", normalizedAddress);
            return false;
        }
        
        List<UserLoginMethod> userMethods = loginMethodRepository.findByUserId(userId);
        boolean hasWeb3 = userMethods.stream()
                .anyMatch(m -> m.getAuthProvider() == AuthProvider.WEB3);
        if (hasWeb3) {
            log.error("User already has a Web3 wallet bound");
            return false;
        }
        
        for (UserLoginMethod method : loginMethodRepository.findAll()) {
            if (method.getUser().getId().equals(userId) && 
                method.getAuthProvider() == AuthProvider.WEB3) {
                log.error("User already has a Web3 wallet bound");
                return false;
            }
        }
        
        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.error("User not found: {}", userId);
            return false;
        }
        
        UserEntity user = userOpt.get();
        
        UserLoginMethod newMethod = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(AuthProvider.WEB3)
                .providerUserId(normalizedAddress)
                .isPrimary(false)
                .isVerified(true)
                .linkedAt(Instant.now())
                .build();
        loginMethodRepository.save(newMethod);
        
        log.info("Bound Web3 wallet {} to user {}", normalizedAddress, userId);
        
        return true;
    }

    public boolean isWalletBound(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        return loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress)
                .isPresent();
    }
}
