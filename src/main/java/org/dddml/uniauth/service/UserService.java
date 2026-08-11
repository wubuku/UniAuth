package org.dddml.uniauth.service;

import org.dddml.uniauth.dto.RegisterRequest;
import org.dddml.uniauth.dto.UserDto;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

/**
 * 用户业务逻辑服务
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginMethodService loginMethodService;
    private final UserLoginMethodRepository loginMethodRepository;
    private final CanonicalEmailService canonicalEmailService;
    private final PasswordPolicyService passwordPolicyService;
    private final OAuth2BindingIntentService oauth2BindingIntentService;

    /**
     * 本地用户注册
     */
    public UserDto register(RegisterRequest request) {
        String username = canonicalEmailService.canonicalizeLoginIdentifier(
                request.getUsername()
        );
        String email = canonicalEmailService.canonicalize(request.getEmail());
        passwordPolicyService.validateNewPassword(request.getPassword());

        // 检查本地用户名是否已被使用
        if (loginMethodService.findByLocalUsername(username) != null) {
            throw new IllegalArgumentException("Username already exists");
        }

        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already exists");
        }

        // 创建新用户实体
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());  // 生成 UUID
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName(request.getDisplayName());
        Set<String> authorities = new HashSet<>();
        authorities.add("ROLE_USER");
        user.setAuthorities(authorities);  // 默认权限
        user.setEnabled(true);
        user.setEmailVerified(true);

        userRepository.save(user);

        // 创建本地登录方式
        UserLoginMethod loginMethod = UserLoginMethod.builder()
            .id(UUID.randomUUID().toString())  // 生成 UUID
            .user(user)
            .authProvider(UserLoginMethod.AuthProvider.LOCAL)
            .localUsername(username)
            .localPasswordHash(passwordEncoder.encode(request.getPassword()))
            .isPrimary(true)
            .isVerified(false)
            .build();

        user.addLoginMethod(loginMethod);
        userRepository.save(user);

        return convertToDto(user);
    }

    /**
     * 使用邮箱验证码注册用户
     */
    public UserDto registerWithEmailVerification(String email, Map<String, Object> metadata) {
        String passwordHash = (String) metadata.get("password");
        String displayName = (String) metadata.getOrDefault("displayName", extractDisplayNameFromEmail(email));

        if (loginMethodRepository.existsByLocalUsername(email)) {
            throw new IllegalArgumentException("Email already registered");
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(email);
        user.setEmail(email);
        user.setEmailIdentityType(UserEntity.EmailIdentityType.VERIFIED_CONTACT);
        user.setDisplayName(displayName);
        Set<String> authorities = new HashSet<>();
        authorities.add("ROLE_USER");
        user.setAuthorities(authorities);
        user.setEnabled(true);
        user.setEmailVerified(true);

        userRepository.save(user);

        UserLoginMethod loginMethod = UserLoginMethod.builder()
            .id(UUID.randomUUID().toString())
            .user(user)
            .authProvider(UserLoginMethod.AuthProvider.LOCAL)
            .localUsername(email)
            .localPasswordHash(passwordHash)
            .isPrimary(true)
            .isVerified(true)
            .build();

        user.addLoginMethod(loginMethod);
        userRepository.save(user);

        return convertToDto(user);
    }

    private String extractDisplayNameFromEmail(String email) {
        return email.split("@")[0];
    }

    /**
     * 本地用户登录 - 验证用户名和密码
     */
    @Transactional
    public UserDto login(String username, String password) {
        // 从登录方式表查找本地登录方式
        String normalizedUsername =
                canonicalEmailService.canonicalizeLoginIdentifier(username);
        passwordPolicyService.validateCredentialInput(password);
        UserLoginMethod loginMethod =
                loginMethodService.findByLocalUsername(normalizedUsername);
        if (loginMethod == null) {
            throw new RuntimeException("User not found");
        }

        // 验证密码
        if (!passwordEncoder.matches(password, loginMethod.getLocalPasswordHash())) {
            throw new RuntimeException("Invalid password");
        }

        // 更新最后使用时间
        loginMethodService.updateLastUsedAt(loginMethod.getId());

        return convertToDto(loginMethod.getUser());
    }

    public OAuthAuthenticationResult completeOAuth(
            OAuth2ProviderProfile profile,
            String state,
            String sessionId) {
        var bindingContext = oauth2BindingIntentService.consume(
                state,
                sessionId,
                profile.registrationId()
        );
        UserLoginMethod existingMethod = loginMethodService.findByOAuth2Provider(
                profile.provider(),
                profile.subject()
        );

        if (bindingContext.isPresent()) {
            OAuth2BindingIntentService.BindingContext context =
                    bindingContext.orElseThrow();
            if (existingMethod != null) {
                throw new OAuth2BindingConflictException(
                        "OAuth2 credential could not be bound"
                );
            }
            UserEntity user = requireEnabledUser(
                    context.userId(),
                    context.securityVersion()
            );
            loginMethodService.bindOAuth2LoginMethod(
                    user.getId(),
                    profile.provider(),
                    profile.subject(),
                    profile.email(),
                    profile.displayName()
            );
            UserEntity updatedUser = requireEnabledUser(
                    context.userId(),
                    context.securityVersion() + 1
            );
            return new OAuthAuthenticationResult(
                    convertToDto(updatedUser),
                    true,
                    context.authTime()
            );
        }

        if (existingMethod != null) {
            UserEntity user = existingMethod.getUser();
            requireEnabledUser(user);
            existingMethod.setProviderEmail(profile.email());
            existingMethod.setProviderUsername(profile.displayName());
            existingMethod.updateLastUsedAt();
            user.setLastLoginAt(java.time.LocalDateTime.now());
            loginMethodRepository.save(existingMethod);
            userRepository.save(user);
            return new OAuthAuthenticationResult(
                    convertToDto(user),
                    false,
                    java.time.Instant.now()
            );
        }

        return createOAuthUser(profile);
    }

    /**
     * 获取当前用户信息
     */
    @Transactional(readOnly = true)
    public UserDto getCurrentUser(String username) {
        UserEntity user = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));
        return convertToDto(user);
    }

    /**
     * 根据用户ID获取用户信息
     */
    @Transactional(readOnly = true)
    public UserDto getUserById(String userId) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        return convertToDto(user);
    }

    public UserDto getOrCreateOAuthUser(String provider,
                                        String providerUserId,
                                        String email,
                                        String name,
                                        String picture) {
        UserLoginMethod.AuthProvider authProvider =
                UserLoginMethod.AuthProvider.valueOf(provider.toUpperCase());
        String registrationId = authProvider == UserLoginMethod.AuthProvider.TWITTER
                ? "x"
                : provider.toLowerCase();
        return completeOAuth(
                new OAuth2ProviderProfile(
                        registrationId,
                        authProvider,
                        providerUserId,
                        email,
                        email != null,
                        name,
                        picture
                ),
                null,
                null
        ).user();
    }

    public UserDto convertToDto(UserEntity user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setDisplayName(user.getDisplayName());
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setAuthorities(user.getAuthorities());
        
        // 获取主要登录方式的provider信息
        if (!user.getLoginMethods().isEmpty()) {
            // 找到主要登录方式
            UserLoginMethod primaryMethod = user.getLoginMethods().stream()
                .filter(UserLoginMethod::isPrimary)
                .findFirst()
                .orElse(user.getLoginMethods().iterator().next());
            
            dto.setProvider(providerName(primaryMethod.getAuthProvider()));
        }
        
        return dto;
    }

    private OAuthAuthenticationResult createOAuthUser(
            OAuth2ProviderProfile profile) {
        if (profile.email() != null
                && userRepository.findByEmail(profile.email()).isPresent()) {
            throw new LoginMethodConflictException(
                    "OAuth2 credential could not be linked"
            );
        }
        String opaqueIdentity = opaqueIdentity();
        String email = profile.email() != null
                ? profile.email()
                : opaqueIdentity + "@oauth.local";
        UserEntity.EmailIdentityType identityType = profile.email() == null
                ? UserEntity.EmailIdentityType.SYNTHETIC
                : (profile.emailTrusted()
                ? UserEntity.EmailIdentityType.VERIFIED_CONTACT
                : UserEntity.EmailIdentityType.UNVERIFIED_CONTACT);

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(opaqueIdentity);
        user.setEmail(email);
        user.setEmailIdentityType(identityType);
        user.setDisplayName(profile.displayName());
        user.setAvatarUrl(profile.picture());
        user.setEmailVerified(profile.emailTrusted());
        user.setAuthorities(new HashSet<>(Set.of("ROLE_USER")));
        user.setEnabled(true);

        UserLoginMethod loginMethod = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(profile.provider())
                .providerUserId(profile.subject())
                .providerEmail(profile.email())
                .providerUsername(profile.displayName())
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(loginMethod);
        try {
            userRepository.saveAndFlush(user);
            return new OAuthAuthenticationResult(
                    convertToDto(user),
                    false,
                    java.time.Instant.now()
            );
        } catch (DataIntegrityViolationException exception) {
            throw new LoginMethodConflictException(
                    "OAuth2 credential could not be linked"
            );
        }
    }

    private UserEntity requireEnabledUser(String userId, long securityVersion) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(OAuth2BindingIntentRejectedException::new);
        requireEnabledUser(user);
        if (user.getTokenSecurityVersion() != securityVersion) {
            throw new OAuth2BindingIntentRejectedException();
        }
        return user;
    }

    private void requireEnabledUser(UserEntity user) {
        if (!user.isEnabled()) {
            throw new OAuth2BindingIntentRejectedException();
        }
    }

    private String opaqueIdentity() {
        return "usr_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String providerName(UserLoginMethod.AuthProvider provider) {
        return provider == UserLoginMethod.AuthProvider.TWITTER
                ? "x"
                : provider.name().toLowerCase();
    }

    public record OAuthAuthenticationResult(
            UserDto user,
            boolean binding,
            java.time.Instant authTime) {
    }
}
