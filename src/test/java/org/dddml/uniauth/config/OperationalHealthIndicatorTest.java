package org.dddml.uniauth.config;

import org.dddml.uniauth.service.JwtTokenService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalHealthIndicatorTest {

    @Test
    void signingKeyRequiresKeyMaterialAndKid() {
        JwtTokenService jwtTokenService = mock(JwtTokenService.class);
        SigningKeyHealthIndicator indicator =
                new SigningKeyHealthIndicator(jwtTokenService);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void flywayRequiresAnAppliedCurrentMigrationAndNoPendingWork() {
        Flyway flyway = mock(Flyway.class);
        MigrationInfoService info = mock(MigrationInfoService.class);
        when(flyway.info()).thenReturn(info);
        when(info.current()).thenReturn(mock(MigrationInfo.class));
        when(info.pending()).thenReturn(new MigrationInfo[0]);

        FlywayMigrationHealthIndicator indicator =
                new FlywayMigrationHealthIndicator(flyway);
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);

        when(info.pending()).thenReturn(new MigrationInfo[]{
                mock(MigrationInfo.class)
        });
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void flywayFailureDoesNotLeakDetailsThroughHealth() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.info()).thenThrow(new IllegalStateException(
                "jdbc:postgresql://secret-host/private"
        ));

        var health = new FlywayMigrationHealthIndicator(flyway).health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).isEmpty();
    }
}
