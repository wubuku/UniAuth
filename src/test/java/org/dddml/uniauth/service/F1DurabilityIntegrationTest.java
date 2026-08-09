package org.dddml.uniauth.service;

import org.dddml.uniauth.dto.LoginRequest;
import org.dddml.uniauth.entity.EmailDeliveryOutbox;
import org.dddml.uniauth.entity.EmailVerificationCode;
import org.dddml.uniauth.entity.UserEntity;
import org.dddml.uniauth.entity.UserLoginMethod;
import org.dddml.uniauth.repository.EmailDeliveryOutboxRepository;
import org.dddml.uniauth.repository.EmailVerificationCodeRepository;
import org.dddml.uniauth.repository.UserLoginMethodRepository;
import org.dddml.uniauth.repository.UserRepository;
import org.dddml.uniauth.service.email.EmailDeliveryReceipt;
import org.dddml.uniauth.service.email.EmailService;
import org.dddml.uniauth.support.PostgreSqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
    "app.email.verification.hmac-key=test-only-f1-durability-key-32-bytes",
    "app.email.verification.hmac-key-id=test-durability-key-1",
    "app.email.delivery.worker-enabled=false",
    "app.auth.rate-limit.enabled=true",
    "app.auth.rate-limit.window-seconds=60",
    "app.auth.rate-limit.source-limit=100",
    "app.auth.rate-limit.login-limit=3",
    "app.auth.rate-limit.key-secret=test-only-f1-rate-limit-key-32-bytes"
})
@ActiveProfiles("test")
class F1DurabilityIntegrationTest extends PostgreSqlIntegrationTest {

    @Autowired
    private EmailVerificationCodeService challengeService;

    @Autowired
    private EmailDeliveryOutboxProcessor outboxProcessor;

    @Autowired
    private EmailDeliveryOutboxRepository outboxRepository;

    @Autowired
    private EmailVerificationCodeRepository challengeRepository;

    @Autowired
    private AuthRateLimiter authRateLimiter;

    @Autowired
    private CredentialAuthenticationService credentialAuthenticationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserLoginMethodRepository loginMethodRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmailService emailService;

    @BeforeEach
    void resetBoundary() {
        reset(emailService);
        jdbcTemplate.update("DELETE FROM auth_rate_limits");
        when(emailService.findDeliveryByIdempotencyKey(anyString()))
                .thenReturn(Optional.empty());
        when(emailService.enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        )).thenReturn(new EmailDeliveryReceipt(
                "provider-delivery-1",
                EmailDeliveryReceipt.DeliveryState.PENDING
        ));
    }

    @Test
    void challengeAndOutboxCommitBeforeDeliveryAndActivateExactlyOnce() {
        EmailVerificationCodeService.ChallengeDispatch dispatch =
                challengeService.sendVerificationCode(
                        uniqueEmail("outbox"),
                        EmailVerificationCode.VerificationPurpose.REGISTRATION
                );
        EmailVerificationCode pending = challengeRepository.findById(
                dispatch.challengeHandle()
        ).orElseThrow();
        EmailDeliveryOutbox outbox = outboxRepository.findByChallengeId(
                dispatch.challengeHandle()
        ).orElseThrow();

        assertThat(pending.getDeliveryStatus())
                .isEqualTo(EmailVerificationCode.DeliveryStatus.PENDING_DELIVERY);
        assertThat(outbox.getStatus())
                .isEqualTo(EmailDeliveryOutbox.Status.PENDING);

        assertThat(outboxProcessor.processOne(outbox.getId())).isTrue();
        assertThat(outboxProcessor.processOne(outbox.getId())).isFalse();

        assertThat(challengeRepository.findById(dispatch.challengeHandle()))
                .get()
                .satisfies(activated -> {
                    assertThat(activated.getDeliveryStatus())
                            .isEqualTo(EmailVerificationCode.DeliveryStatus.ACTIVE);
                    assertThat(activated.getProviderDeliveryId())
                            .isEqualTo("provider-delivery-1");
                    assertThat(activated.getActivatedAt()).isNotNull();
                });
        assertThat(outboxRepository.findById(outbox.getId()))
                .get()
                .extracting(EmailDeliveryOutbox::getStatus)
                .isEqualTo(EmailDeliveryOutbox.Status.ACCEPTED);
        verify(emailService).enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        );
        assertThat(securityEventCount(
                dispatch.challengeHandle(),
                "EMAIL_DELIVERY_ACCEPTED"
        )).isEqualTo(1);
    }

    @Test
    void reconciliationUsesExistingProviderIdentityWithoutReenqueueing() {
        EmailVerificationCodeService.ChallengeDispatch dispatch =
                challengeService.sendVerificationCode(
                        uniqueEmail("reconcile"),
                        EmailVerificationCode.VerificationPurpose.REGISTRATION
                );
        EmailDeliveryOutbox outbox = outboxRepository.findByChallengeId(
                dispatch.challengeHandle()
        ).orElseThrow();
        when(emailService.findDeliveryByIdempotencyKey(
                outbox.getIdempotencyKey()
        )).thenReturn(Optional.of(new EmailDeliveryReceipt(
                "provider-existing-1",
                EmailDeliveryReceipt.DeliveryState.PROCESSING
        )));

        assertThat(outboxProcessor.processOne(outbox.getId())).isTrue();

        verify(emailService, never()).enqueueTemplateEmail(
                anyString(),
                anyString(),
                anyString(),
                any(),
                anyString(),
                anyString()
        );
        assertThat(challengeRepository.findById(dispatch.challengeHandle()))
                .get()
                .extracting(EmailVerificationCode::getProviderDeliveryId)
                .isEqualTo("provider-existing-1");
    }

    @Test
    void sharedRateLimiterAllowsOnlyTheConfiguredConcurrentReservations()
            throws Exception {
        List<Boolean> results = runConcurrently(
                12,
                () -> {
                    try {
                        authRateLimiter.requireAllowed(
                                AuthRateLimiter.Policy.LOGIN,
                                "127.0.0.1",
                                "same-user@example.invalid"
                        );
                        return true;
                    } catch (AuthRateLimitExceededException exception) {
                        return false;
                    }
                }
        );

        assertThat(results.stream().filter(Boolean::booleanValue).count())
                .isEqualTo(3);
        assertThat(results.stream().filter(value -> !value).count())
                .isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_rate_limits",
                Integer.class
        )).isEqualTo(2);
    }

    @Test
    void securityEventInsertFailureRollsBackSuccessfulCredentialState() {
        String username = "rollback-login-" + UUID.randomUUID();
        UserLoginMethod method = createLocalUser(
                username,
                username + "@example.invalid",
                "integration-password"
        );
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION reject_security_event_insert_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'test security event insert failure';
                END
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER security_events_reject_insert_test
                BEFORE INSERT ON security_events
                FOR EACH ROW
                EXECUTE FUNCTION reject_security_event_insert_test()
                """);

        try {
            LoginRequest request = new LoginRequest();
            request.setUsername(username);
            request.setPassword("integration-password");

            assertThatThrownBy(
                    () -> credentialAuthenticationService.authenticate(request)
            ).isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("""
                    DROP TRIGGER IF EXISTS security_events_reject_insert_test
                    ON security_events
                    """);
            jdbcTemplate.execute(
                    "DROP FUNCTION IF EXISTS reject_security_event_insert_test()"
            );
        }

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT last_used_at IS NULL
                FROM user_login_methods
                WHERE id = ?
                """,
                Boolean.class,
                method.getId()
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT last_login_at IS NULL
                FROM users
                WHERE id = ?
                """,
                Boolean.class,
                method.getUser().getId()
        )).isTrue();
    }

    private int securityEventCount(String subjectId, String eventType) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM security_events
                WHERE subject_id = ?
                  AND event_type = ?
                """,
                Integer.class,
                subjectId,
                eventType
        );
    }

    private UserLoginMethod createLocalUser(
            String username,
            String email,
            String password) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID().toString());
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailIdentityType(
                UserEntity.EmailIdentityType.VERIFIED_CONTACT
        );
        user.setDisplayName("F1 Durability User");
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAuthorities(Set.of("ROLE_USER"));

        UserLoginMethod method = UserLoginMethod.builder()
                .id(UUID.randomUUID().toString())
                .user(user)
                .authProvider(UserLoginMethod.AuthProvider.LOCAL)
                .localUsername(username)
                .localPasswordHash(passwordEncoder.encode(password))
                .isPrimary(true)
                .isVerified(true)
                .build();
        user.addLoginMethod(method);
        userRepository.saveAndFlush(user);
        return method;
    }

    private List<Boolean> runConcurrently(
            int count,
            Callable<Boolean> task) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Concurrent rate limit start timed out"
                        );
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.invalid";
    }
}
