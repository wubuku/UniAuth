package org.dddml.uniauth.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("flywayMigrationHealthIndicator")
public class FlywayMigrationHealthIndicator implements HealthIndicator {

    private final Flyway flyway;

    public FlywayMigrationHealthIndicator(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public Health health() {
        try {
            MigrationInfoService info = flyway.info();
            if (info.current() == null || info.pending().length != 0) {
                return Health.down().build();
            }
            return Health.up().build();
        } catch (RuntimeException exception) {
            return Health.down().build();
        }
    }
}
