package org.dddml.uniauth.service;

import org.dddml.uniauth.entity.UserLoginMethod;

public record OAuth2ProviderProfile(
        String registrationId,
        UserLoginMethod.AuthProvider provider,
        String subject,
        String email,
        boolean emailTrusted,
        String displayName,
        String picture) {
}
