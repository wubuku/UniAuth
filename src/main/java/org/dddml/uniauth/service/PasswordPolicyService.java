package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.config.PasswordPolicyProperties;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordPolicyService {

    private final PasswordPolicyProperties properties;

    public void validateNewPassword(String password) {
        if (password == null
                || password.length() < properties.getMinLength()
                || password.length() > properties.getMaxLength()
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Password does not satisfy the policy");
        }
    }

    public void validateCredentialInput(String password) {
        if (password == null
                || password.isEmpty()
                || password.length() > properties.getMaxLength()
                || password.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid credentials");
        }
    }
}
