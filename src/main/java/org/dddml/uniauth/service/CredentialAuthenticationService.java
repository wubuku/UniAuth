package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.dto.LoginRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CredentialAuthenticationService {

    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO5HhKc1Q8eJjv4zQf0xR6i7jJb3C2WlK";

    private final UserLoginMethodRepository loginMethodRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final CanonicalEmailService canonicalEmailService;
    private final UserService userService;
    private final SecurityEventService securityEventService;

    @Transactional
    public UserDto authenticate(LoginRequest request) {
        String username = canonicalEmailService.canonicalizeLoginIdentifier(
                request.getUsername()
        );
        passwordPolicyService.validateCredentialInput(request.getPassword());

        UserLoginMethod method = loginMethodRepository
                .findByLocalUsername(username)
                .orElse(null);
        if (method == null || method.getLocalPasswordHash() == null) {
            passwordEncoder.matches(request.getPassword(), DUMMY_BCRYPT_HASH);
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!passwordEncoder.matches(
                request.getPassword(),
                method.getLocalPasswordHash()
        )) {
            throw new BadCredentialsException("Invalid credentials");
        }

        UserEntity user = method.getUser();
        if (!user.isEnabled()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        if (passwordEncoder.upgradeEncoding(method.getLocalPasswordHash())) {
            method.setLocalPasswordHash(
                    passwordEncoder.encode(request.getPassword())
            );
        }
        method.updateLastUsedAt();
        user.setLastLoginAt(LocalDateTime.now());
        loginMethodRepository.save(method);
        userRepository.save(user);
        securityEventService.append(
                "PASSWORD_LOGIN_SUCCEEDED",
                user.getId(),
                SecurityEventService.Outcome.SUCCESS,
                null
        );
        return userService.convertToDto(user);
    }
}
