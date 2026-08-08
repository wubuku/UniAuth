package org.dddml.email;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:email_service_test",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.mail.host=localhost",
    "spring.mail.port=25",
    "spring.mail.properties.mail.smtp.auth=false",
    "app.mail.from-email=no-reply@example.test",
    "app.mail.from-name=Email Service Test"
})
class EmailServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
