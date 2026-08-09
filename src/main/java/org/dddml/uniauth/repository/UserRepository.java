package org.dddml.uniauth.repository;

import org.dddml.uniauth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/**
 * 用户Repository接口
 */
@Repository
public interface UserRepository extends JpaRepository<UserEntity, String> {
    Optional<UserEntity> findByUsername(String username);
    Optional<UserEntity> findByEmail(String email);

    @Query(
        value = """
                SELECT login_methods_revision
                FROM users
                WHERE id = :userId
                """,
        nativeQuery = true
    )
    Optional<Long> findLoginMethodsRevision(@Param("userId") String userId);

    @Query(
        value = """
                SELECT token_security_version
                FROM users
                WHERE id = :userId
                """,
        nativeQuery = true
    )
    Optional<Long> findTokenSecurityVersion(@Param("userId") String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE users
                SET login_methods_revision = login_methods_revision + 1
                WHERE id = :userId
                  AND login_methods_revision = :expectedRevision
                """,
        nativeQuery = true
    )
    int compareAndIncrementLoginMethodsRevision(
        @Param("userId") String userId,
        @Param("expectedRevision") long expectedRevision
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
                UPDATE users
                SET token_security_version = token_security_version + 1
                WHERE id = :userId
                  AND token_security_version = :expectedVersion
                """,
        nativeQuery = true
    )
    int compareAndIncrementTokenSecurityVersion(
        @Param("userId") String userId,
        @Param("expectedVersion") long expectedVersion
    );
}
