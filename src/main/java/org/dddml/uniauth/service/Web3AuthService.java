package org.dddml.uniauth.service;

import org.dddml.uniauth.dto.web3.Web3NonceResponse;
import org.dddml.uniauth.dto.web3.Web3LoginRequest;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.entity.UserLoginMethod.AuthProvider;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.util.Web3SignatureUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class Web3AuthService {

    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final Web3NonceService web3NonceService;
    private final TokenSessionTransactionService tokenSessionTransactionService;
    private final SecurityEventService securityEventService;
    
    @Value("${app.web3.domain:localhost}")
    private String domain;
    
    @Value("${app.web3.nonce-expiration-seconds:300}")
    private long nonceExpirationSeconds;

    @Value("${app.web3.chain-id:1}")
    private int chainId;

    @Value("${app.web3.message-format}")
    private String messageFormat;

    @Transactional
    public Web3NonceResponse generateNonce(
            String walletAddress,
            String trustedSource) {
        if (!Web3SignatureUtils.isValidAddress(walletAddress)) {
            throw new IllegalArgumentException("Invalid wallet address format");
        }

        String normalizedAddress = Web3SignatureUtils.normalizeAddress(walletAddress);
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = issuedAt.plusSeconds(nonceExpirationSeconds);
        String message = buildSiweMessage(normalizedAddress, nonce, issuedAt, expiresAt);
        
        String challengeHandle = web3NonceService.saveNonce(
                normalizedAddress,
                nonce,
                message,
                trustedSource,
                expiresAt
        );

        log.info("Web3 nonce generated");

        return new Web3NonceResponse(
                challengeHandle,
                nonce,
                message,
                chainId,
                nonceExpirationSeconds
        );
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
                "Chain ID: %7$d\n" +
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
                expiresAt.toString(),
                chainId
        );
    }

    @Transactional(noRollbackFor = Web3AuthenticationRejectedException.class)
    public AuthenticationResult authenticate(Web3LoginRequest request) {
        String normalizedAddress = verifyAndConsume(request);
        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress);

        if (existingMethod.isPresent()) {
            UserLoginMethod method = existingMethod.get();
            UserEntity user = method.getUser();
            requireEnabled(user);
            method.updateLastUsedAt();
            user.setLastLoginAt(LocalDateTime.now());
            loginMethodRepository.save(method);
            userRepository.save(user);
            return new AuthenticationResult(user, false);
        }

        String opaqueIdentity = opaqueIdentity();
        UserEntity newUser = UserEntity.builder()
                .id(UUID.randomUUID().toString())
                .username(opaqueIdentity)
                .email(opaqueIdentity + "@web3.local")
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
        try {
            loginMethodRepository.saveAndFlush(newMethod);
            log.info("Web3 account created");
            return new AuthenticationResult(newUser, true);
        } catch (DataIntegrityViolationException exception) {
            throw new Web3BindingConflictException();
        }
    }

    @Transactional(noRollbackFor = Web3AuthenticationRejectedException.class)
    public void bindWalletToUser(
            String userId,
            long expectedSecurityVersion,
            Web3LoginRequest request) {
        String normalizedAddress = verifyAndConsume(request);

        Optional<UserLoginMethod> existingMethod = loginMethodRepository
                .findByAuthProviderAndProviderUserId(AuthProvider.WEB3, normalizedAddress);
        if (existingMethod.isPresent()) {
            throw new Web3BindingConflictException();
        }
        if (loginMethodRepository.findByUserIdAndAuthProvider(
                userId,
                AuthProvider.WEB3
        ).isPresent()) {
            throw new Web3BindingConflictException();
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(Web3BindingConflictException::new);
        requireEnabled(user);
        if (user.getTokenSecurityVersion() != expectedSecurityVersion) {
            throw new Web3BindingConflictException();
        }

        UserLoginMethod newMethod = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(AuthProvider.WEB3)
                .providerUserId(normalizedAddress)
                .isPrimary(false)
                .isVerified(true)
                .linkedAt(Instant.now())
                .build();
        try {
            loginMethodRepository.saveAndFlush(newMethod);
            tokenSessionTransactionService.incrementSecurityVersionAndRevoke(
                    userId,
                    "WEB3_CREDENTIAL_ADDED"
            );
            securityEventService.append(
                    "WEB3_CREDENTIAL_BOUND",
                    userId,
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            log.info("Web3 wallet binding completed");
        } catch (DataIntegrityViolationException exception) {
            securityEventService.appendIndependent(
                    "WEB3_CREDENTIAL_BIND_CONFLICT",
                    userId,
                    SecurityEventService.Outcome.DENIED,
                    "UNIQUE_CONFLICT"
            );
            throw new Web3BindingConflictException();
        }
    }

    private String verifyAndConsume(Web3LoginRequest request) {
        try {
            String normalizedAddress = Web3SignatureUtils.normalizeAddress(
                    request.getWalletAddress()
            );
            if (request.getChainId() == null
                    || request.getChainId() != chainId
                    || !Web3SignatureUtils.verifySignature(
                    request.getMessage(),
                    request.getSignature(),
                    normalizedAddress
            )
                    || !web3NonceService.consumeNonce(
                    request.getChallengeHandle(),
                    normalizedAddress,
                    request.getNonce(),
                    request.getMessage()
            )) {
                throw new Web3AuthenticationRejectedException();
            }
            return normalizedAddress;
        } catch (Web3AuthenticationRejectedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new Web3AuthenticationRejectedException();
        }
    }

    private void requireEnabled(UserEntity user) {
        if (!user.isEnabled()) {
            throw new Web3AuthenticationRejectedException();
        }
    }

    private String opaqueIdentity() {
        return "usr_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record AuthenticationResult(UserEntity user, boolean newUser) {
    }
}
