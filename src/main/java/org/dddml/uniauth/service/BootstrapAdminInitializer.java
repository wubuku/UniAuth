package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.config.BootstrapAdminProperties;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Creates one explicitly configured local administrator when it is absent.
 *
 * Re-running the initializer is intentionally non-destructive: an existing
 * password is never replaced by the configured bootstrap password.
 */
@Slf4j
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements CommandLineRunner {

    private static final String ROLE_USER = "ROLE_USER";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final CanonicalEmailService canonicalEmailService;
    private final BootstrapAdminProperties properties;

    @Override
    @Transactional
    public void run(String... args) {
        BootstrapIdentity identity = validateIdentity();

        Optional<UserEntity> byUsername =
                userRepository.findByUsername(identity.username());
        Optional<UserEntity> byEmail =
                userRepository.findByEmail(identity.email());
        Optional<UserLoginMethod> byLocalUsername =
                loginMethodRepository.findByLocalUsername(identity.username());

        UserEntity existing = resolveExisting(
                identity,
                byUsername,
                byEmail,
                byLocalUsername
        );
        if (existing != null) {
            validateExisting(existing, identity.username());
            log.info("Bootstrap administrator already exists; no password was changed");
            return;
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(identity.username());
        user.setEmail(identity.email());
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName(identity.displayName());
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(new HashSet<>(Set.of(ROLE_USER, ROLE_ADMIN)));

        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(identity.username())
                .localPasswordHash(
                        passwordEncoder.encode(properties.getPassword())
                )
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);

        try {
            UserEntity saved = userRepository.saveAndFlush(user);
            log.info(
                    "Bootstrap administrator created with username={} userId={}",
                    saved.getUsername(),
                    saved.getId()
            );
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException(
                    "Bootstrap administrator could not be created because "
                            + "the configured identity conflicts with existing data",
                    exception
            );
        }
    }

    private BootstrapIdentity validateIdentity() {
        String username;
        String email;
        try {
            username = canonicalEmailService.canonicalizeLoginIdentifier(
                    properties.getUsername()
            );
            email = canonicalEmailService.canonicalize(properties.getEmail());
            passwordPolicyService.validateNewPassword(properties.getPassword());
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Bootstrap administrator configuration is invalid",
                    exception
            );
        }

        if (canonicalEmailService.looksLikeEmail(username)
                && !username.equals(email)) {
            throw new IllegalStateException(
                    "Bootstrap administrator email-shaped username must match email"
            );
        }
        String displayName = properties.getDisplayName();
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = username;
        } else {
            displayName = displayName.trim();
        }
        if (displayName.length() > 255
                || displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(
                    "Bootstrap administrator display name is invalid"
            );
        }
        return new BootstrapIdentity(username, email, displayName);
    }

    private UserEntity resolveExisting(
            BootstrapIdentity identity,
            Optional<UserEntity> byUsername,
            Optional<UserEntity> byEmail,
            Optional<UserLoginMethod> byLocalUsername) {
        Set<String> ids = new HashSet<>();
        byUsername.map(UserEntity::getId).ifPresent(ids::add);
        byEmail.map(UserEntity::getId).ifPresent(ids::add);
        byLocalUsername.map(method -> method.getUser().getId()).ifPresent(ids::add);
        if (ids.size() > 1) {
            throw new IllegalStateException(
                    "Bootstrap administrator identity conflicts with existing data"
            );
        }
        if (ids.isEmpty()) {
            return null;
        }
        UserEntity user = byUsername.orElseGet(
                () -> byEmail.orElseGet(
                        () -> byLocalUsername.orElseThrow().getUser()
                )
        );
        if (!identity.username().equals(user.getUsername())
                || !identity.email().equals(user.getEmail())) {
            throw new IllegalStateException(
                    "Bootstrap administrator identity conflicts with existing data"
            );
        }
        return user;
    }

    private void validateExisting(UserEntity user, String username) {
        if (!user.isEnabled()
                || !user.getAuthorities().contains(ROLE_ADMIN)) {
            throw new IllegalStateException(
                    "Configured bootstrap administrator exists but is disabled "
                            + "or lacks ROLE_ADMIN"
            );
        }
        UserLoginMethod local = loginMethodRepository
                .findByUserIdAndAuthProvider(
                        user.getId(),
                        UserLoginMethod.AuthProvider.LOCAL
                )
                .orElseThrow(() -> new IllegalStateException(
                        "Configured bootstrap administrator has no local login method"
                ));
        if (!username.equals(local.getLocalUsername())
                || local.getLocalPasswordHash() == null
                || local.getLocalPasswordHash().isBlank()) {
            throw new IllegalStateException(
                    "Configured bootstrap administrator local credentials are invalid"
            );
        }
    }

    private record BootstrapIdentity(
            String username,
            String email,
            String displayName) {
    }
}
