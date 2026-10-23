package org.peekaboot.backend.tracing.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.listener.Listeners;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.EventListenerMarker;
import org.peekaboot.backend.testsupport.RecordingObservations;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class EventListenerObservationContextTest {

    /** The span goes in front of the bean's own advisors, so a listener's REQUIRES_NEW transaction opens inside it. */
    @Test
    void aListenersOwnTransactionOpensInsideItsSpan() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(ListenerContext.class)) {
            publishInsideParentObservation(context, new Listeners.OrderPlaced("PK-1"));

            assertThat(context.getBean(RecordingTransactionManager.class).observationsAtBegin)
                    .containsExactly(EventListenerMarker.OBSERVATION_NAME);
        }
    }

    /** Async's advisor stays in front, so the span opens on the executor thread and times the work. */
    @Test
    void anAsyncListenersSpanOpensOnTheExecutorThread() throws InterruptedException {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(ListenerContext.class)) {
            publishInsideParentObservation(context, new Listeners.OrderPlaced("PK-1"));

            assertThat(context.getBean(Listeners.AsyncListener.class).awaitHandled())
                    .isTrue();
            RecordingObservations observations = context.getBean(RecordingObservations.class);
            assertThat(observations.startedNamed(EventListenerMarker.OBSERVATION_NAME))
                    .filteredOn(start -> "AsyncListener#onOrderPlaced".equals(start.getContextualName()))
                    .singleElement()
                    .satisfies(start -> {
                        assertThat(observations.startThreadOf(start))
                                .startsWith(ListenerContext.EXECUTOR_THREAD_PREFIX);
                        assertThat(start.getParentObservation().getContextView().getName())
                                .isEqualTo("parent");
                    });
        }
    }

    @Test
    void aBeanWithAPrivateListenerMethodStillStartsAndHearsItsEvents() {
        try (AnnotationConfigApplicationContext context = startedWith(Listeners.PrivateMethod.class)) {
            context.publishEvent(new Listeners.OrderPlaced("PK-1"));
            context.publishEvent(new Listeners.RejectedOrder("out of stock"));

            assertThat(context.getBean(Listeners.PrivateMethod.class).heard()).hasSize(2);
        }
    }

    @Test
    void aListenerThatImplementsAnInterfaceStartsAndIsObserved() {
        try (AnnotationConfigApplicationContext context = startedWith(Listeners.NotifyingListener.class)) {
            publishInsideParentObservation(context, new Listeners.OrderPlaced("PK-1"));

            assertThat(context.getBean(RecordingObservations.class).startedNamed(EventListenerMarker.OBSERVATION_NAME))
                    .extracting(Observation.Context::getContextualName)
                    .containsExactly("NotifyingListener#onOrderPlaced");
        }
    }

    private static AnnotationConfigApplicationContext startedWith(Class<?> listenerClass) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(ObservedContext.class);
        context.registerBean(listenerClass);
        context.refresh();
        return context;
    }

    private static void publishInsideParentObservation(AnnotationConfigApplicationContext context, Object event) {
        context.getBean(RecordingObservations.class).runInsideParentObservation(() -> context.publishEvent(event));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableAsync
    @Import(ObservedContext.class)
    static class ListenerContext {

        static final String EXECUTOR_THREAD_PREFIX = "listener-test-";

        @Bean
        RecordingTransactionManager transactionManager(ObservationRegistry observationRegistry) {
            return new RecordingTransactionManager(observationRegistry);
        }

        @Bean
        SimpleAsyncTaskExecutor taskExecutor(ObservationRegistry observationRegistry) {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(EXECUTOR_THREAD_PREFIX);
            executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
            return executor;
        }

        @Bean
        Listeners.RequiresNew requiresNewListener() {
            return new Listeners.RequiresNew();
        }

        @Bean
        Listeners.AsyncListener asyncListener() {
            return new Listeners.AsyncListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservedContext {

        @Bean
        static EventListenerObservationPostProcessor eventListenerObservationPostProcessor(
                ObjectProvider<ObservationRegistry> observationRegistry) {
            return new EventListenerObservationPostProcessor(observationRegistry::getObject);
        }

        @Bean
        RecordingObservations recordingObservations() {
            return new RecordingObservations();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingObservations observations) {
            return observations.registry();
        }
    }

    /** Records which observation was current when each transaction began; it manages no resource. */
    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private final transient ObservationRegistry observationRegistry;
        final List<String> observationsAtBegin = new CopyOnWriteArrayList<>();

        RecordingTransactionManager(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            Observation current = observationRegistry.getCurrentObservation();
            observationsAtBegin.add(
                    current == null ? "none" : current.getContext().getName());
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
