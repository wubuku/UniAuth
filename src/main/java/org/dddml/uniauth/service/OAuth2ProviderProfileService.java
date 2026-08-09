package org.dddml.uniauth.service;

import lombok.RequiredArgsConstructor;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OAuth2ProviderProfileService {

    public static final String VERIFIED_GITHUB_EMAIL =
            "uniauth_verified_primary_email";

    private final CanonicalEmailService canonicalEmailService;

    public OAuth2ProviderProfile resolve(Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            throw new IllegalArgumentException("OAuth2 authentication is invalid");
        }
        String registrationId = token.getAuthorizedClientRegistrationId();
        OAuth2User principal = token.getPrincipal();
        return switch (registrationId) {
            case "google" -> google(registrationId, principal);
            case "github" -> github(registrationId, principal);
            case "x" -> x(registrationId, principal);
            default -> throw new IllegalArgumentException(
                    "OAuth2 provider is not supported"
            );
        };
    }

    private OAuth2ProviderProfile google(
            String registrationId,
            OAuth2User principal) {
        if (!(principal instanceof OidcUser oidcUser)) {
            throw new IllegalArgumentException(
                    "Google OIDC principal is invalid"
            );
        }
        String email = optionalString(
                principal.getAttributes(),
                "email",
                254
        );
        boolean trusted = Boolean.TRUE.equals(
                principal.getAttribute("email_verified")
        );
        return new OAuth2ProviderProfile(
                registrationId,
                UserLoginMethod.AuthProvider.GOOGLE,
                boundedRequired(oidcUser.getSubject(), 255),
                canonicalEmail(email),
                trusted && email != null,
                optionalString(principal.getAttributes(), "name", 255),
                optionalString(principal.getAttributes(), "picture", 2048)
        );
    }

    private OAuth2ProviderProfile github(
            String registrationId,
            OAuth2User principal) {
        String email = optionalString(
                principal.getAttributes(),
                VERIFIED_GITHUB_EMAIL,
                254
        );
        return new OAuth2ProviderProfile(
                registrationId,
                UserLoginMethod.AuthProvider.GITHUB,
                boundedRequired(attributeAsString(principal, "id"), 255),
                canonicalEmail(email),
                email != null,
                optionalString(principal.getAttributes(), "login", 255),
                optionalString(principal.getAttributes(), "avatar_url", 2048)
        );
    }

    private OAuth2ProviderProfile x(
            String registrationId,
            OAuth2User principal) {
        return new OAuth2ProviderProfile(
                registrationId,
                UserLoginMethod.AuthProvider.TWITTER,
                boundedRequired(attributeAsString(principal, "id"), 255),
                null,
                false,
                optionalString(principal.getAttributes(), "username", 255),
                optionalString(
                        principal.getAttributes(),
                        "profile_image_url",
                        2048
                )
        );
    }

    private String attributeAsString(OAuth2User principal, String name) {
        Object value = principal.getAttribute(name);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException(
                "OAuth2 provider subject is invalid"
        );
    }

    private String optionalString(
            Map<String, Object> attributes,
            String name,
            int maxLength) {
        Object value = attributes.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(
                    "OAuth2 provider attribute is invalid"
            );
        }
        if (stringValue.isBlank()) {
            return null;
        }
        return boundedRequired(stringValue, maxLength);
    }

    private String boundedRequired(String value, int maxLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "OAuth2 provider attribute is invalid"
            );
        }
        return value;
    }

    private String canonicalEmail(String email) {
        return email == null ? null : canonicalEmailService.canonicalize(email);
    }
}
