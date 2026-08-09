package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.dto.VerifyEmailRequest;
import org.dddml.uniauth.entity.EmailVerificationCode.VerificationPurpose;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final EmailVerificationCodeService verificationCodeService;
    private final CanonicalEmailService canonicalEmailService;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final UserService userService;
    private final SecurityEventService securityEventService;

    public RegistrationPreview preview(
            String submittedUsername,
            String submittedEmail,
            String password,
            String displayName) {
        String email = canonicalEmailService.canonicalize(submittedEmail);
        String username = canonicalEmailService.canonicalizeLoginIdentifier(
                submittedUsername
        );
        if (canonicalEmailService.looksLikeEmail(username)
                && !username.equals(email)) {
            throw new IllegalArgumentException(
                    "Email-shaped username must match the verified email"
            );
        }
        passwordPolicyService.validateNewPassword(password);
        validateDisplayName(displayName);
        return new RegistrationPreview(username, email);
    }

    @Transactional(
        noRollbackFor = VerificationChallengeRejectedException.class
    )
    public UserDto complete(VerifyEmailRequest request) {
        RegistrationPreview preview = preview(
                request.getUsername(),
                request.getEmail(),
                request.getPassword(),
                request.getDisplayName()
        );

        if (userRepository.findByEmail(preview.email()).isPresent()
                || userRepository.findByUsername(preview.username()).isPresent()
                || loginMethodRepository
                        .findByLocalUsername(preview.username())
                        .isPresent()) {
            throw new IllegalArgumentException(
                    "Registration could not be completed"
            );
        }

        EmailVerificationCodeService.VerificationResult verification =
                verificationCodeService.verifyCode(
                        request.getChallengeHandle(),
                        preview.email(),
                        request.getVerificationCode(),
                        VerificationPurpose.REGISTRATION
                );
        if (!verification.isSuccess()) {
            throw new VerificationChallengeRejectedException();
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(preview.username());
        user.setEmail(preview.email());
        user.setEmailIdentityType(
                UserEntity.EmailIdentityType.VERIFIED_CONTACT
        );
        user.setDisplayName(normalizedDisplayName(
                request.getDisplayName(),
                preview.email()
        ));
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(new HashSet<>(Set.of("ROLE_USER")));

        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(preview.username())
                .localPasswordHash(
                        passwordEncoder.encode(request.getPassword())
                )
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);
        try {
            UserEntity saved = userRepository.saveAndFlush(user);
            securityEventService.append(
                    "LOCAL_REGISTRATION_COMPLETED",
                    saved.getId(),
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            return userService.convertToDto(saved);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException(
                    "Registration could not be completed"
            );
        }
    }

    private void validateDisplayName(String displayName) {
        if (displayName != null
                && (displayName.length() > 255
                || displayName.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Invalid display name");
        }
    }

    private String normalizedDisplayName(String displayName, String email) {
        if (displayName == null || displayName.trim().isEmpty()) {
            return email.substring(0, email.indexOf('@'));
        }
        return displayName.trim();
    }

    public record RegistrationPreview(String username, String email) {
    }
}
