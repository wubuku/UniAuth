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
import java.time.temporal.ChronoUnit;
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
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = issuedAt.plusSeconds(nonceExpirationSeconds);
        String message = buildSiweMessage(normalizedAddress, nonce, issuedAt, expiresAt);
        
        web3NonceService.saveNonce(
                normalizedAddress,
                nonce,
                message,
                expiresAt
        );

        log.info("Web3 nonce generated");

        return new Web3NonceResponse(nonce, message, nonceExpirationSeconds);
    }

    private String buildSiweMessage(
            String walletAddress,
            String nonce,
            Instant issuedAt,
            Instant expiresAt
    ) {
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
                issuedAt.toString(),
                expiresAt.toString()
        );
    }

    @Transactional
    public boolean verifySignature(
            String walletAddress,
            String message,
            String signature,
            String nonce,
            Integer chainId
    ) {
        try {
            String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
            if (chainId != null && chainId != 1) {
                log.warn("Web3 chain ID mismatch");
                return false;
            }

            boolean isValid = Web3SignatureUtils.verifySignature(message, signature, normalizedAddress);

            if (isValid && web3NonceService.consumeNonce(normalizedAddress, nonce, message)) {
                log.info("Web3 signature verification succeeded");
                return true;
            }

            log.warn("Web3 signature verification failed or challenge was not current");
            return false;
        } catch (Exception e) {
            log.warn("Web3 signature verification could not be completed");
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
                .emailIdentityType(UserEntity.EmailIdentityType.SYNTHETIC)
                .displayName("Web3 User")
                .emailVerified(false)
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
        
        log.info("Web3 account created");
        
        return newUser;
    }

    @Transactional
    public boolean bindWalletToUser(String userId, String walletAddress) {
        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
        
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress);
        
        if (existingMethod.isPresent()) {
            log.warn("Web3 wallet is already bound");
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
            log.warn("Web3 binding target user was not found");
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
        
        log.info("Web3 wallet binding completed");
        
        return true;
    }

    public boolean isWalletBound(String walletAddress) {
        String normalizedAddress = walletAddress.toLowerCase();
        return loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress)
                .isPresent();
    }
}
