package org.dddml.uniauth.service;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Reachability evidence for {@link CustomUserDetailsService}.
 *
 * The bean is not called by any UniAuth controller (local login goes through
 * {@code CredentialAuthenticationService}), but Spring Security's
 * {@code InitializeUserDetailsManagerConfigurer} does register it with the
 * global {@code AuthenticationManager} at startup. Because the framework can
 * therefore reach {@code loadUserByUsername} if a basic/form login is ever
 * enabled, its contract is pinned directly instead of being deleted.
 *
 * See docs/drafts/TEST_HARDENING_IMPLEMENTATION.md section 11 for the full
 * reachability analysis.
 */
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    private static final String USERNAME = "alice";
    private static final String PASSWORD_HASH = "$2a$10$hash";

    @Mock
    private UserLoginMethodRepository loginMethodRepository;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new CustomUserDetailsService(loginMethodRepository);
    }

    @Test
    void loadsAnEnabledLocalUserWithItsPasswordHashAndAuthorities() {
        UserLoginMethod method = localMethod(
                UserEntity.builder()
                        .id("11111111-1111-1111-1111-111111111111")
                        .username(USERNAME)
                        .enabled(true)
                        .authorities(Set.of("ROLE_USER", "ROLE_ADMIN"))
                        .build()
        );
        when(loginMethodRepository.findByLocalUsername(USERNAME))
                .thenReturn(Optional.of(method));

        UserDetails details = service.loadUserByUsername(USERNAME);

        assertThat(details.getUsername()).isEqualTo(USERNAME);
        assertThat(details.getPassword()).isEqualTo(PASSWORD_HASH);
        assertThat(details.getAuthorities())
                .extracting(authority -> authority.getAuthority())
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    @Test
    void rejectsAnUnknownLocalUsername() {
        when(loginMethodRepository.findByLocalUsername(USERNAME))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername(USERNAME))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining(USERNAME);
    }

    @Test
    void rejectsADisabledUser() {
        UserLoginMethod method = localMethod(
                UserEntity.builder()
                        .id("11111111-1111-1111-1111-111111111111")
                        .username(USERNAME)
                        .enabled(false)
                        .authorities(Set.of("ROLE_USER"))
                        .build()
        );
        when(loginMethodRepository.findByLocalUsername(USERNAME))
                .thenReturn(Optional.of(method));

        assertThatThrownBy(() -> service.loadUserByUsername(USERNAME))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining(USERNAME);
    }

    @Test
    void failsClosedWhenALocalMethodHasNoPasswordHash() {
        // V6 allows LOCAL rows created by the email-code flow to carry a null
        // password hash. A principal with a null hash cannot be constructed, so
        // the service fails instead of authenticating an empty credential.
        UserLoginMethod method = localMethod(
                UserEntity.builder()
                        .id("11111111-1111-1111-1111-111111111111")
                        .username(USERNAME)
                        .enabled(true)
                        .authorities(Set.of("ROLE_USER"))
                        .build()
        );
        method.setLocalPasswordHash(null);
        when(loginMethodRepository.findByLocalUsername(USERNAME))
                .thenReturn(Optional.of(method));

        assertThatThrownBy(() -> service.loadUserByUsername(USERNAME))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private UserLoginMethod localMethod(UserEntity user) {
        return UserLoginMethod.builder()
                .id("22222222-2222-2222-2222-222222222222")
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(USERNAME)
                .localPasswordHash(PASSWORD_HASH)
                .build();
    }
}
