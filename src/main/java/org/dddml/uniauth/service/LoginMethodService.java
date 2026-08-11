package org.dddml.uniauth.service;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.entity.UserLoginMethod.AuthProvider;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 登录方式管理服务
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class LoginMethodService {

    private static final String USER_PROVIDER_CONSTRAINT = "uk_user_login_provider";
    private static final String PROVIDER_SUBJECT_CONSTRAINT = "uk_provider_user";
    private static final String LOCAL_USERNAME_CONSTRAINT = "uk_local_username";
    private static final String PRIMARY_CONSTRAINT = "uk_login_methods_one_primary";

    private final UserLoginMethodRepository loginMethodRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CanonicalEmailService canonicalEmailService;
    private final PasswordPolicyService passwordPolicyService;
    private final TokenSessionTransactionService tokenSessionTransactionService;
    private final SecurityEventService securityEventService;

    /**
     * 获取用户的所有登录方式
     */
    @Transactional(readOnly = true)
    public List<UserLoginMethod> getUserLoginMethods(String userId) {
        return loginMethodRepository.findByUserId(userId);
    }

    /**
     * 为用户绑定OAuth2登录方式
     * 
     * @throws IllegalStateException 如果该提供商已被该用户绑定
     * @throws OAuth2BindingConflictException 如果OAuth2账户已被其他用户绑定，
     *         包括并发唯一约束裁决的失败请求
     */
    public UserLoginMethod bindOAuth2LoginMethod(
            String userId,
            AuthProvider provider,
            String providerUserId,
            String providerEmail,
            String providerUsername) {

        log.info("Binding OAuth2 login method for provider {}", provider);

        if (provider == null || provider == AuthProvider.LOCAL) {
            throw new IllegalArgumentException("无效的OAuth2登录方式");
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("OAuth2账户标识不能为空");
        }

        // 1. 检查用户是否已经绑定该提供商
        if (loginMethodRepository.findByUserIdAndAuthProvider(userId, provider).isPresent()) {
            throw new IllegalStateException("用户已绑定该登录方式");
        }
        
        // 2. 检查OAuth2账户是否已被其他用户绑定
        loginMethodRepository.findByAuthProviderAndProviderUserId(provider, providerUserId)
            .ifPresent(existing -> {
                if (!existing.getUser().getId().equals(userId)) {
                    throw new OAuth2BindingConflictException(
                            "该OAuth2账户已被其他用户绑定"
                    );
                }
            });
        
        // 3. 创建新的登录方式
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        UserLoginMethod loginMethod = UserLoginMethod.builder()
            .id(UUID.randomUUID().toString())  // 生成 UUID
            .user(user)
            .authProvider(provider)
            .providerUserId(providerUserId)
            .providerEmail(providerEmail)
            .providerUsername(providerUsername)
            .isVerified(true)  // OAuth2用户默认已验证
            .isPrimary(false)  // 新绑定的不是主登录方式
            .build();
        
        try {
            UserLoginMethod saved = loginMethodRepository.saveAndFlush(loginMethod);
            tokenSessionTransactionService.incrementSecurityVersionAndRevoke(
                    userId,
                    "OAUTH_CREDENTIAL_ADDED"
            );
            securityEventService.append(
                    "OAUTH2_CREDENTIAL_BOUND",
                    userId,
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            log.info("OAuth2 login method binding completed");
            return saved;
        } catch (DataIntegrityViolationException exception) {
            securityEventService.appendIndependent(
                    "OAUTH2_CREDENTIAL_BIND_CONFLICT",
                    userId,
                    SecurityEventService.Outcome.DENIED,
                    "UNIQUE_CONFLICT"
            );
            throw translateBindingConflict(exception);
        }
    }

    /**
     * 通过本地用户名查找登录方式
     * 用于本地登录验证
     */
    @Transactional(readOnly = true)
    public UserLoginMethod findByLocalUsername(String username) {
        String normalized =
                canonicalEmailService.canonicalizeLoginIdentifier(username);
        return loginMethodRepository.findByLocalUsername(normalized)
            .orElse(null);
    }

    /**
     * 通过OAuth2信息查找登录方式
     * 用于OAuth2登录
     */
    @Transactional(readOnly = true)
    public UserLoginMethod findByOAuth2Provider(AuthProvider provider, String providerUserId) {
        return loginMethodRepository.findByAuthProviderAndProviderUserId(provider, providerUserId)
            .orElse(null);
    }

    /**
     * 更新登录方式的最后使用时间
     */
    public void updateLastUsedAt(String loginMethodId) {
        loginMethodRepository.findById(loginMethodId).ifPresent(method -> {
            method.updateLastUsedAt();
            loginMethodRepository.save(method);
        });
    }

    /**
     * 移除登录方式
     * 
     * @throws IllegalStateException 如果是最后一个登录方式
     */
    public void removeLoginMethod(String userId, String loginMethodId) {
        log.info("Removing login method");

        long expectedRevision = currentLoginMethodsRevision(userId);

        // 1. 检查登录方式是否属于该用户
        UserLoginMethod method = loginMethodRepository.findById(loginMethodId)
            .orElseThrow(() -> new IllegalArgumentException("登录方式不存在"));
        
        if (!method.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("无权移除该登录方式");
        }
        
        // 2. 在事务内检查是否至少有两个登录方式
        List<UserLoginMethod> methods = loginMethodRepository.findByUserId(userId);
        if (methods.size() <= 1) {
            throw new IllegalStateException("不能移除最后一个登录方式");
        }

        boolean removingPrimary = method.isPrimary();
        String replacementPrimaryId = null;

        // 3. 如果是主登录方式，需要先设置另一个为主登录方式
        if (removingPrimary) {
            replacementPrimaryId = methods.stream()
                .filter(m -> !m.getId().equals(loginMethodId))
                .map(UserLoginMethod::getId)
                .findFirst()
                .orElseThrow();
        }

        claimLoginMethodMutation(
                userId,
                expectedRevision,
                "登录方式已被并发修改，请重试"
        );

        if (removingPrimary) {
            try {
                loginMethodRepository.clearPrimaryForUser(userId);
                if (loginMethodRepository.setPrimaryForUser(
                        userId,
                        replacementPrimaryId
                ) != 1) {
                    throw new IllegalArgumentException("替代登录方式不存在");
                }
                log.info("Replacement primary login method selected");
            } catch (DataIntegrityViolationException exception) {
                throw translatePrimaryConflict(exception);
            }
        }

        // 4. 删除登录方式
        loginMethodRepository.deleteById(loginMethodId);
        loginMethodRepository.flush();
        tokenSessionTransactionService.incrementSecurityVersionAndRevoke(
                userId,
                "LOGIN_METHOD_REMOVED"
        );
        securityEventService.append(
                "LOGIN_METHOD_REMOVED",
                userId,
                SecurityEventService.Outcome.SUCCESS,
                null
        );
        log.info("Login method removed successfully");
    }

    /**
     * 设置主登录方式
     */
    public void setPrimaryLoginMethod(String userId, String loginMethodId) {
        log.info("Setting primary login method");

        long expectedRevision = currentLoginMethodsRevision(userId);

        // 1. 验证登录方式属于该用户
        UserLoginMethod method = loginMethodRepository.findById(loginMethodId)
            .orElseThrow(() -> new IllegalArgumentException("登录方式不存在"));
        
        if (!method.getUser().getId().equals(userId)) {
            throw new IllegalArgumentException("无权设置该登录方式");
        }

        claimLoginMethodMutation(
                userId,
                expectedRevision,
                "主登录方式已被并发修改，请重试"
        );

        try {
            // Bulk updates make the write order explicit and avoid relying on ORM flush ordering.
            loginMethodRepository.clearPrimaryForUser(userId);
            if (loginMethodRepository.setPrimaryForUser(userId, loginMethodId) != 1) {
                throw new IllegalArgumentException("登录方式不存在");
            }
            loginMethodRepository.flush();
            securityEventService.append(
                    "LOGIN_METHOD_PRIMARY_CHANGED",
                    userId,
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            log.info("Primary login method set successfully");
        } catch (DataIntegrityViolationException exception) {
            throw translatePrimaryConflict(exception);
        }
    }

    /**
     * 为已登录用户添加本地登录方式（用于SSO用户添加本地密码）
     * 
     * 场景：用户通过SSO登录后，想添加本地用户名/密码登录方式
     * 
     * @throws IllegalStateException 如果用户已有本地登录方式
     * @throws IllegalArgumentException 如果用户名已被使用
     */
    public UserLoginMethod addLocalLoginMethod(
            String userId,
            String username,
            String password) {
        
        log.info("Adding local login method");
        String normalizedUsername =
                canonicalEmailService.canonicalizeLoginIdentifier(username);
        passwordPolicyService.validateNewPassword(password);
        
        // 1. 检查用户是否已有本地登录方式
        if (loginMethodRepository.findByUserIdAndAuthProvider(userId, AuthProvider.LOCAL).isPresent()) {
            throw new IllegalStateException("该用户已有本地登录方式，无法重复添加");
        }
        
        // 2. 检查用户名是否已被使用
        if (loginMethodRepository.existsByLocalUsername(normalizedUsername)) {
            throw new IllegalArgumentException("用户名已被使用，请选择其他用户名");
        }
        
        // 3. 获取用户
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (canonicalEmailService.looksLikeEmail(normalizedUsername)
                && (user.getEmailIdentityType()
                    != UserEntity.EmailIdentityType.VERIFIED_CONTACT
                || !normalizedUsername.equals(user.getEmail()))) {
            throw new IllegalArgumentException(
                    "邮箱形式的本地用户名必须等于当前已验证邮箱"
            );
        }
        
        // 4. 创建新的本地登录方式
        UserLoginMethod loginMethod = UserLoginMethod.builder()
            .id(UUID.randomUUID().toString())  // 生成 UUID
            .user(user)
            .authProvider(AuthProvider.LOCAL)
            .localUsername(normalizedUsername)
            .localPasswordHash(passwordEncoder.encode(password))
            .isPrimary(false)  // 新添加的不是主登录方式
            .isVerified(false)  // 未验证（可选：可以改为true如果不需要验证）
            .build();
        
        try {
            UserLoginMethod saved = loginMethodRepository.saveAndFlush(loginMethod);
            tokenSessionTransactionService.incrementSecurityVersionAndRevoke(
                    userId,
                    "LOCAL_CREDENTIAL_ADDED"
            );
            securityEventService.append(
                    "LOCAL_CREDENTIAL_ADDED",
                    userId,
                    SecurityEventService.Outcome.SUCCESS,
                    null
            );
            log.info("Local login method added");
            return saved;
        } catch (DataIntegrityViolationException exception) {
            String constraint = constraintName(exception);
            if (USER_PROVIDER_CONSTRAINT.equals(constraint)) {
                throw new IllegalStateException("该用户已有本地登录方式，无法重复添加");
            }
            if (LOCAL_USERNAME_CONSTRAINT.equals(constraint)) {
                throw new IllegalArgumentException("用户名已被使用，请选择其他用户名");
            }
            throw exception;
        }
    }

    /**
     * 更新用户密码
     * 用于密码重置功能
     *
     * @param username 用户名（邮箱或本地用户名）
     * @param newPassword 新密码（明文，会自动加密）
     * @throws IllegalArgumentException 如果用户不存在
     */
    public void updatePassword(String username, String newPassword) {
        log.info("Updating local password");
        passwordPolicyService.validateNewPassword(newPassword);
        
        String normalized =
                canonicalEmailService.canonicalizeLoginIdentifier(username);
        UserLoginMethod loginMethod = loginMethodRepository.findByLocalUsername(normalized)
            .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        
        if (loginMethod.getLocalPasswordHash() == null) {
            throw new IllegalArgumentException("该用户不是本地登录方式，无法通过此方式重置密码");
        }
        
        loginMethod.setLocalPasswordHash(passwordEncoder.encode(newPassword));
        loginMethodRepository.save(loginMethod);
        tokenSessionTransactionService.incrementSecurityVersionAndRevoke(
                loginMethod.getUser().getId(),
                "PASSWORD_CHANGED"
        );
        
        log.info("Local password updated");
    }

    private RuntimeException translateBindingConflict(
            DataIntegrityViolationException exception) {
        String constraint = constraintName(exception);
        if (USER_PROVIDER_CONSTRAINT.equals(constraint)) {
            return new IllegalStateException("用户已绑定该登录方式");
        }
        if (PROVIDER_SUBJECT_CONSTRAINT.equals(constraint)) {
            return new OAuth2BindingConflictException("该OAuth2账户已被绑定");
        }
        return exception;
    }

    private RuntimeException translatePrimaryConflict(
            DataIntegrityViolationException exception) {
        if (PRIMARY_CONSTRAINT.equals(constraintName(exception))) {
            return new LoginMethodConflictException("主登录方式已被并发修改，请重试");
        }
        return exception;
    }

    private long currentLoginMethodsRevision(String userId) {
        return userRepository.findLoginMethodsRevision(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void claimLoginMethodMutation(
            String userId,
            long expectedRevision,
            String conflictMessage) {
        if (userRepository.compareAndIncrementLoginMethodsRevision(
                userId,
                expectedRevision
        ) != 1) {
            throw new LoginMethodConflictException(conflictMessage);
        }
    }

    private String constraintName(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintViolation.getConstraintName() != null) {
                return constraintViolation.getConstraintName();
            }
            current = current.getCause();
        }
        return null;
    }
}
