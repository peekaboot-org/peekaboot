package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A task thread started from a request must not hold the request's web-app class loader: Tomcat
 * reports every live thread holding it as a leak when the context stops. Boot's builder makes the
 * executor, customizers and all, the way it makes {@code applicationTaskExecutor}; a fresh one
 * guarantees the task gets a new thread rather than one an earlier test started.
 */
class TaskThreadClassLoaderIT extends TestingAppITBase {

    @Autowired
    private ThreadPoolTaskExecutorBuilder executorBuilder;

    @Autowired
    private ServletContext servletContext;

    @Autowired
    private ConfigurableListableBeanFactory beanFactory;

    @Test
    void aTaskThreadStartedFromARequestHoldsTheApplicationClassLoader() throws Exception {
        ClassLoader webAppClassLoader = servletContext.getClassLoader();
        assertThat(webAppClassLoader).isNotSameAs(beanFactory.getBeanClassLoader());

        ThreadPoolTaskExecutor executor = executorBuilder.build();
        executor.initialize();
        try {
            CompletableFuture<ClassLoader> taskClassLoader = new CompletableFuture<>();
            Thread requestThread = new Thread(() -> executor.execute(
                    () -> taskClassLoader.complete(Thread.currentThread().getContextClassLoader())));
            requestThread.setContextClassLoader(webAppClassLoader);
            requestThread.start();

            assertThat(taskClassLoader.get(15, TimeUnit.SECONDS)).isSameAs(beanFactory.getBeanClassLoader());
        } finally {
            executor.shutdown();
        }
    }
}
