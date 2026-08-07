package org.dddml.uniauth.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {

    private static final String DEMO_PASSWORD = "password123";
    private static final Pattern SAFE_DATABASE_NAME = Pattern.compile(
            "^(?:uniauth[-_])?(?:test|demo)(?:[-_][a-z0-9]+)*$",
            Pattern.CASE_INSENSITIVE
    );

    private final UserRepository userRepository;
    private final UserLoginMethodRepository loginMethodRepository;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;
    private final DemoDataProperties properties;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        requireDisposableDatabase();

        upsertLocalOnlyUser();
        upsertSsoOnlyUser();
        upsertMixedUser();

        log.info("Demo data initialization completed for 3 managed accounts");
    }

    private void requireDisposableDatabase() throws Exception {
        if (!properties.enabled()) {
            throw new IllegalStateException("Demo data initialization must be explicitly enabled");
        }
        if (!properties.disposable()) {
            throw new IllegalStateException(
                    "Demo data initialization requires app.demo-data.disposable=true"
            );
        }

        String jdbcUrl;
        try (Connection connection = dataSource.getConnection()) {
            jdbcUrl = connection.getMetaData().getURL();
        }

        String databaseName = extractDatabaseName(jdbcUrl);
        if (databaseName == null || !SAFE_DATABASE_NAME.matcher(databaseName).matches()) {
            throw new IllegalStateException(
                    "Demo data initialization rejected unsafe database name"
            );
        }
    }

    private String extractDatabaseName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return null;
        }

        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            String withoutQuery = jdbcUrl.split("\\?", 2)[0];
            int separator = withoutQuery.lastIndexOf('/');
            return separator >= 0 ? withoutQuery.substring(separator + 1) : null;
        }

        return null;
    }

    private void upsertLocalOnlyUser() {
        UserEntity user = upsertUser(
                "testlocal",
                "testlocal@example.com",
                "Test Local User"
        );
        resetPrimaryMethods(user);
        upsertLocalMethod(user, "testlocal", true);
    }

    private void upsertSsoOnlyUser() {
        UserEntity user = upsertUser(
                "testsso@example.com",
                "testsso@example.com",
                "Test SSO User"
        );
        resetPrimaryMethods(user);
        upsertGoogleMethod(user, "mock_google_testsso", true);
    }

    private void upsertMixedUser() {
        UserEntity user = upsertUser(
                "testboth",
                "testboth@example.com",
                "Test Both User"
        );
        resetPrimaryMethods(user);
        upsertLocalMethod(user, "testboth", true);
        upsertGoogleMethod(user, "mock_google_testboth", false);
    }

    private UserEntity upsertUser(String username, String email, String displayName) {
        Optional<UserEntity> existing = userRepository.findByEmail(email);
        if (existing.isEmpty()) {
            existing = userRepository.findByUsername(username);
        }

        UserEntity user = existing.orElseGet(UserEntity::new);
        if (user.getId() == null || user.getId().isBlank()) {
            user.setId(UUID.randomUUID().toString());
        }
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setAuthorities(new HashSet<>(Set.of("ROLE_USER")));
        return userRepository.save(user);
    }

    private void resetPrimaryMethods(UserEntity user) {
        loginMethodRepository.findByUserId(user.getId()).forEach(method -> {
            if (method.isPrimary()) {
                method.setPrimary(false);
                loginMethodRepository.save(method);
            }
        });
    }

    private void upsertLocalMethod(UserEntity user, String username, boolean primary) {
        UserLoginMethod method = loginMethodRepository
                .findByUserIdAndAuthProvider(user.getId(), UserLoginMethod.AuthProvider.LOCAL)
                .orElseGet(() -> newMethod(user, UserLoginMethod.AuthProvider.LOCAL));
        method.setLocalUsername(username);
        method.setLocalPasswordHash(passwordEncoder.encode(DEMO_PASSWORD));
        method.setVerified(true);
        method.setPrimary(primary);
        loginMethodRepository.save(method);
    }

    private void upsertGoogleMethod(UserEntity user, String providerUserId, boolean primary) {
        UserLoginMethod method = loginMethodRepository
                .findByUserIdAndAuthProvider(user.getId(), UserLoginMethod.AuthProvider.GOOGLE)
                .orElseGet(() -> newMethod(user, UserLoginMethod.AuthProvider.GOOGLE));
        method.setProviderUserId(providerUserId);
        method.setProviderEmail(user.getEmail());
        method.setProviderUsername(user.getDisplayName());
        method.setVerified(true);
        method.setPrimary(primary);
        loginMethodRepository.save(method);
    }

    private UserLoginMethod newMethod(
            UserEntity user,
            UserLoginMethod.AuthProvider provider) {
        return UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(provider)
                .build();
    }
}
