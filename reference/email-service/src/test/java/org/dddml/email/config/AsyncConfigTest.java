package org.dddml.email.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringJUnitConfig(AsyncConfig.class)
class AsyncConfigTest {

    @Autowired
    @Qualifier("emailExecutor")
    private Executor emailExecutor;

    @Test
    void saturatedAsyncExecutorDoesNotEscapeThePersistentQueueFallback() throws Exception {
        assertThat(emailExecutor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) emailExecutor;
        CountDownLatch runningTasks = new CountDownLatch(20);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();

        try {
            for (int index = 0; index < 120; index++) {
                taskExecutor.execute(() -> {
                    runningTasks.countDown();
                    try {
                        releaseTasks.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            assertThat(runningTasks.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatCode(() -> taskExecutor.execute(() -> rejectedTaskRan.set(true)))
                .doesNotThrowAnyException();
            assertThat(rejectedTaskRan).isFalse();
        } finally {
            releaseTasks.countDown();
        }
    }
}
