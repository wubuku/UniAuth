package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.entity.UserLoginMethod.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserLoginMethodRepository extends JpaRepository<UserLoginMethod, String> {
    
    /**
     * 查找用户的所有登录方式
     */
    List<UserLoginMethod> findByUserId(String userId);
    
    /**
     * 查找用户的特定登录方式
     */
    Optional<UserLoginMethod> findByUserIdAndAuthProvider(String userId, AuthProvider authProvider);
    
    /**
     * 通过OAuth2提供商和用户ID查找
     */
    Optional<UserLoginMethod> findByAuthProviderAndProviderUserId(
        AuthProvider authProvider, String providerUserId);
    
    /**
     * 通过本地用户名查找
     */
    Optional<UserLoginMethod> findByLocalUsername(String localUsername);
    
    /**
     * 查找用户的主登录方式
     */
    Optional<UserLoginMethod> findByUserIdAndIsPrimary(String userId, boolean isPrimary);
    
    /**
     * 检查OAuth2账户是否已被绑定
     */
    boolean existsByAuthProviderAndProviderUserId(AuthProvider authProvider, String providerUserId);
    
    /**
     * 检查本地用户名是否已被使用
     */
    boolean existsByLocalUsername(String localUsername);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE user_login_methods
                SET local_password_hash = :newPasswordHash
                WHERE id = :methodId
                  AND user_id = :userId
                  AND auth_provider = 'LOCAL'
                  AND local_password_hash = :expectedPasswordHash
                """,
        nativeQuery = true
    )
    int compareAndSetLocalPassword(
        @Param("methodId") String methodId,
        @Param("userId") String userId,
        @Param("expectedPasswordHash") String expectedPasswordHash,
        @Param("newPasswordHash") String newPasswordHash
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE user_login_methods
                SET is_primary = false
                WHERE user_id = :userId
                  AND is_primary IS TRUE
                """,
        nativeQuery = true
    )
    int clearPrimaryForUser(@Param("userId") String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE user_login_methods
                SET is_primary = true
                WHERE id = :loginMethodId
                  AND user_id = :userId
                """,
        nativeQuery = true
    )
    int setPrimaryForUser(
        @Param("userId") String userId,
        @Param("loginMethodId") String loginMethodId
    );
}
