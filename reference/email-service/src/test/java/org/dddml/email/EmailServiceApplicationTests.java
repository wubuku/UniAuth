package org.dddml.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.flyway.fail-on-missing-locations=true",
    "spring.flyway.locations=classpath:db/migration/postgresql",
    "spring.flyway.table=email_service_flyway_schema_history",
    "spring.flyway.default-schema=public",
    "spring.flyway.schemas=public",
    "spring.flyway.baseline-on-migrate=false",
    "spring.flyway.baseline-version=0",
    "spring.flyway.clean-disabled=true",
    "spring.flyway.validate-migration-naming=true",
    "spring.flyway.validate-on-migrate=true",
    "spring.flyway.out-of-order=false",
    "spring.sql.init.mode=never",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.jpa.open-in-view=false",
    "spring.mail.host=localhost",
    "spring.mail.port=25",
    "spring.mail.properties.mail.smtp.auth=false",
    "spring.mail.properties.mail.smtp.starttls.enable=false",
    "spring.mail.properties.mail.smtp.starttls.required=false",
    "spring.mail.properties.mail.smtp.ssl.enable=false",
    "spring.mail.properties.mail.smtp.ssl.checkserveridentity=true",
    "app.mail.from-email=no-reply@example.test",
    "app.mail.from-name=Email Service Test"
})
@Testcontainers
class EmailServiceApplicationTests {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("email_service_application_test")
            .withUsername("email_application_test")
            .withPassword("email_application_test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Test
    void contextLoads() {
        assertThat(mailSender.getHost()).isEqualTo("localhost");
        assertThat(mailSender.getPort()).isEqualTo(25);
        assertThat(mailSender.getJavaMailProperties())
            .containsEntry("mail.smtp.ssl.checkserveridentity", "true");
    }
}
