package org.dddml.email;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:email_service_test",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
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
class EmailServiceApplicationTests {

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
