package org.dddml.uniauth.config;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.BootstrapAdminInitializer;
import org.dddml.uniauth.service.CanonicalEmailService;
import org.dddml.uniauth.service.PasswordPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BootstrapAdminInitializerTest {

    private UserRepository userRepository;
    private UserLoginMethodRepository loginMethodRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordPolicyService passwordPolicyService;
    private BootstrapAdminProperties properties;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        loginMethodRepository = mock(UserLoginMethodRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        passwordPolicyService = new PasswordPolicyService(
                new PasswordPolicyProperties()
        );
        properties = new BootstrapAdminProperties();
        properties.setEnabled(true);
        properties.setUsername("admin");
        properties.setEmail("admin@example.invalid");
        properties.setPassword("Initial-admin-password");
        properties.setDisplayName("Initial Administrator");

        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(loginMethodRepository.findByLocalUsername(any()))
                .thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(passwordEncoder.encode("Initial-admin-password"))
                .thenReturn("encoded-initial-password");
    }

    @Test
    void createsAnAdminWithLocalUsernameAndPassword() {
        initializer().run();

        var userCaptor = org.mockito.ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        UserEntity user = userCaptor.getValue();

        assertThat(user.getUsername()).isEqualTo("admin");
        assertThat(user.getEmail()).isEqualTo("admin@example.invalid");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getAuthorities())
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(user.getLoginMethods()).singleElement().satisfies(method -> {
            assertThat(method.getAuthProvider())
                    .isEqualTo(UserLoginMethod.AuthProvider.LOCAL);
            assertThat(method.getLocalUsername()).isEqualTo("admin");
            assertThat(method.getLocalPasswordHash())
                    .isEqualTo("encoded-initial-password");
            assertThat(method.isPrimary()).isTrue();
            assertThat(method.isVerified()).isTrue();
        });
    }

    @Test
    void neverOverwritesAnExistingAdministratorPassword() {
        UserEntity existing = new UserEntity();
        existing.setId("existing-admin");
        existing.setUsername("admin");
        existing.setEmail("admin@example.invalid");
        existing.setEnabled(true);
        existing.setAuthorities(Set.of("ROLE_USER", "ROLE_ADMIN"));
        UserLoginMethod method = UserLoginMethod.builder()
                .id("existing-local")
                .user(existing)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername("admin")
                .localPasswordHash("existing-hash")
                .isPrimary(true)
                .isVerified(true)
                .build();
        existing.addLoginMethod(method);
        when(userRepository.findByUsername("admin"))
                .thenReturn(Optional.of(existing));
        when(userRepository.findByEmail("admin@example.invalid"))
                .thenReturn(Optional.of(existing));
        when(loginMethodRepository.findByLocalUsername("admin"))
                .thenReturn(Optional.of(method));
        when(loginMethodRepository.findByUserIdAndAuthProvider(
                "existing-admin",
                UserLoginMethod.AuthProvider.LOCAL
        )).thenReturn(Optional.of(method));

        initializer().run();

        verify(userRepository, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void rejectsAWeakConfiguredBootstrapPasswordBeforeDatabaseWrites() {
        properties.setPassword("short");

        assertThatThrownBy(() -> initializer().run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configuration");

        verifyNoInteractions(userRepository, loginMethodRepository);
    }

    private BootstrapAdminInitializer initializer() {
        return new BootstrapAdminInitializer(
                userRepository,
                loginMethodRepository,
                passwordEncoder,
                passwordPolicyService,
                new CanonicalEmailService(),
                properties
        );
    }
}
