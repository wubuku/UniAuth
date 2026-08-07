package org.dddml.uniauth.config;

import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DemoDataInitializerTest {

    private UserRepository userRepository;
    private UserLoginMethodRepository loginMethodRepository;
    private PasswordEncoder passwordEncoder;
    private DataSource dataSource;
    private Connection connection;
    private DatabaseMetaData databaseMetaData;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        loginMethodRepository = mock(UserLoginMethodRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        databaseMetaData = mock(DatabaseMetaData.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(passwordEncoder.encode(any())).thenReturn("encoded-password");
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(any())).thenReturn(Optional.empty());
        when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(loginMethodRepository.findByUserId(any())).thenReturn(List.of());
        when(loginMethodRepository.findByUserIdAndAuthProvider(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void refusesInitializationWithoutDisposableFlag() throws Exception {
        when(databaseMetaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/uniauth_test");

        DemoDataInitializer initializer = initializer(new DemoDataProperties(true, false));

        assertThatThrownBy(initializer::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disposable");
        verifyNoInteractions(userRepository, loginMethodRepository);
    }

    @Test
    void refusesUnsafeDatabaseName() throws Exception {
        when(databaseMetaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/customer_accounts");

        DemoDataInitializer initializer = initializer(new DemoDataProperties(true, true));

        assertThatThrownBy(initializer::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database name");
        verifyNoInteractions(userRepository, loginMethodRepository);
    }

    @Test
    void safeInitializationNeverDeletesRepositoryData() throws Exception {
        when(databaseMetaData.getURL()).thenReturn("jdbc:postgresql://localhost:5432/uniauth_test");

        initializer(new DemoDataProperties(true, true)).run();

        verify(userRepository, never()).deleteAll();
        verify(loginMethodRepository, never()).deleteAll();
        verify(userRepository, times(3)).save(any(UserEntity.class));
        verify(loginMethodRepository, times(4)).save(any());
    }

    private DemoDataInitializer initializer(DemoDataProperties properties) {
        return new DemoDataInitializer(
                userRepository,
                loginMethodRepository,
                passwordEncoder,
                dataSource,
                properties
        );
    }
}
