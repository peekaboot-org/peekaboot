package org.peekaboot.backend.tracing.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.listener.Listeners;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.EventListenerMarker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
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
            assertThat(context.getBean(RecordingHandler.class).started)
                    .filteredOn(start -> start.name().equals(EventListenerMarker.OBSERVATION_NAME))
                    .filteredOn(start -> start.contextualName().equals("AsyncListener#onOrderPlaced"))
                    .singleElement()
                    .satisfies(start -> {
                        assertThat(start.thread()).startsWith(ListenerContext.EXECUTOR_THREAD_PREFIX);
                        assertThat(start.parent()).isEqualTo("parent");
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

            assertThat(context.getBean(RecordingHandler.class).started)
                    .filteredOn(start -> start.name().equals(EventListenerMarker.OBSERVATION_NAME))
                    .extracting(Start::contextualName)
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
        Observation.createNotStarted("parent", context.getBean(ObservationRegistry.class))
                .observe(() -> context.publishEvent(event));
    }

    /** Stands in for Boot's ContextPropagatingTaskDecorator, carrying the observation onto the executor thread. */
    private static Runnable continueObservation(ObservationRegistry registry, Runnable task) {
        Observation submitting = registry.getCurrentObservation();
        return submitting == null ? task : () -> submitting.scoped(task);
    }

    private static ObservationRegistry registryReportingTo(RecordingHandler handler) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        return registry;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableAsync
    static class ListenerContext {

        static final String EXECUTOR_THREAD_PREFIX = "listener-test-";

        @Bean
        static EventListenerObservationPostProcessor eventListenerObservationPostProcessor(
                ObjectProvider<ObservationRegistry> observationRegistry) {
            return new EventListenerObservationPostProcessor(observationRegistry::getObject);
        }

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingHandler handler) {
            return registryReportingTo(handler);
        }

        @Bean
        RecordingTransactionManager transactionManager(ObservationRegistry observationRegistry) {
            return new RecordingTransactionManager(observationRegistry);
        }

        @Bean
        SimpleAsyncTaskExecutor taskExecutor(ObservationRegistry observationRegistry) {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(EXECUTOR_THREAD_PREFIX);
            executor.setTaskDecorator(task -> continueObservation(observationRegistry, task));
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
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingHandler handler) {
            return registryReportingTo(handler);
        }
    }

    record Start(String name, String contextualName, String thread, String parent) {}

    static class RecordingHandler implements ObservationHandler<Observation.Context> {

        final List<Start> started = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            String parent = context.getParentObservation() == null
                    ? ""
                    : context.getParentObservation().getContextView().getName();
            started.add(new Start(
                    context.getName(),
                    String.valueOf(context.getContextualName()),
                    Thread.currentThread().getName(),
                    parent));
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
