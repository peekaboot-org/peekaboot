package org.peekaboot.backend.tracing.async;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import com.example.listener.Listeners;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RecordingObservations;
import org.peekaboot.backend.tracing.listener.EventListenerObservationPostProcessor;
import org.peekaboot.backend.tracing.tags.AsyncTaskMarker;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncAnnotationAdvisor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Against Spring's own {@code @EnableAsync} proxies, executor and uncaught-exception handling. */
class AsyncTaskFailurePostProcessorTest {

    private static final String REJECTION = "enrichment rejected";

    @Test
    void recordsAVoidMethodsFailureAndStillHandsItToTheApplicationsHandler() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncContext.class)) {
            insideParentObservation(context, () -> {
                context.getBean(Enrichment.class).enrich();
                return null;
            });

            RecordingUncaughtHandler handler = context.getBean(RecordingUncaughtHandler.class);
            assertThat(handler.awaitHandled()).isTrue();
            assertThat(handler.handled).singleElement().satisfies(failure -> assertRecordedOnTheTask(context, failure));
        }
    }

    @Test
    void recordsAFutureMethodsFailureAndStillFailsTheCallersFuture() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncContext.class)) {
            Future<String> future = insideParentObservation(
                    context, () -> context.getBean(Enrichment.class).enrichAsFuture());

            assertRecordedOnTheTask(context, failureOf(future));
        }
    }

    @Test
    void recordsACompletableFutureMethodsFailureAndStillFailsTheCallersFuture() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncContext.class)) {
            CompletableFuture<String> future = insideParentObservation(
                    context, () -> context.getBean(Enrichment.class).enrichAsCompletableFuture());

            assertRecordedOnTheTask(context, failureOf(future));
        }
    }

    @Test
    void recordsAFailedFutureTheMethodReturns() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncContext.class)) {
            CompletableFuture<String> future = insideParentObservation(
                    context, () -> context.getBean(Enrichment.class).returnFailedFuture());

            assertRecordedOnTheTask(context, failureOf(future));
        }
    }

    @Test
    void recordsNothingWhenTheMethodSucceeds() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncContext.class)) {
            CompletableFuture<String> future = insideParentObservation(
                    context, () -> context.getBean(Enrichment.class).succeed());

            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo("enriched");
            assertThat(asyncTaskError(context)).isNull();
        }
    }

    /**
     * Directly inside the async hand-off, so it runs on the executor thread, and outside every
     * advisor the bean already had, so their failures are seen too.
     */
    @Test
    void joinsTheProxyDirectlyBehindSpringsAsyncAdvisor() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(AsyncContext.class, ListenerContext.class)) {
            Advisor[] advisors = ((Advised) context.getBean(Listeners.AsyncListener.class)).getAdvisors();

            assertThat(advisors).hasSize(3);
            assertThat(advisors[0]).isInstanceOf(AsyncAnnotationAdvisor.class);
            assertThat(advisors[1].getAdvice()).isInstanceOf(AsyncTaskFailureInterceptor.class);
        }
    }

    /** Peekaboot joins the proxies Spring made; it makes none and changes none's kind. */
    @Test
    void keepsAnInterfaceProxyAnInterfaceProxyAndRecordsItsFailure() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AsyncContext.class)) {
            Notifier notifier = context.getBean(Notifier.class);

            CompletableFuture<String> future = insideParentObservation(context, notifier::notifyCustomer);

            assertThat(AopUtils.isJdkDynamicProxy(notifier)).isTrue();
            assertRecordedOnTheTask(context, failureOf(future));
        }
    }

    /** Matches exactly the methods Spring hands off, so an application's own async annotation counts. */
    @Test
    void honoursACustomAsyncAnnotation() throws Exception {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(CustomAnnotationContext.class)) {
            CompletableFuture<String> future = insideParentObservation(
                    context, () -> context.getBean(OffloadedEnrichment.class).enrich());

            assertRecordedOnTheTask(context, failureOf(future));
        }
    }

    @Test
    void joinsAProxyOnlyOnce() {
        AsyncTaskFailurePostProcessor postProcessor = new AsyncTaskFailurePostProcessor();
        Object proxy = asyncProxy(false);

        postProcessor.postProcessAfterInitialization(proxy, "enrichment");
        postProcessor.postProcessAfterInitialization(proxy, "enrichment");

        assertThat(((Advised) proxy).getAdvisors()).hasSize(2);
    }

    /** Spring cannot add an advisor to a frozen proxy. */
    @Test
    void leavesAFrozenProxyAloneAndSaysWhy() {
        Object frozen = asyncProxy(true);

        try (LogCapture logs = LogCapture.attach(AsyncTaskFailurePostProcessor.class, Level.DEBUG)) {
            assertThat(new AsyncTaskFailurePostProcessor().postProcessAfterInitialization(frozen, "enrichment"))
                    .isSameAs(frozen);

            assertThat(((Advised) frozen).getAdvisors()).hasSize(1);
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).contains("enrichment").contains("frozen");
            });
        }
    }

    private static IllegalStateException rejected() {
        return new IllegalStateException(REJECTION);
    }

    /** The application sees the very exception the span records. */
    private static void assertRecordedOnTheTask(AnnotationConfigApplicationContext context, Throwable failure) {
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage(REJECTION);
        assertThat(asyncTaskError(context)).isSameAs(failure);
    }

    private static Object asyncProxy(boolean frozen) {
        ProxyFactory proxyFactory = new ProxyFactory(new Enrichment());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvisor(new AsyncAnnotationAdvisor());
        proxyFactory.setFrozen(frozen);
        return proxyFactory.getProxy();
    }

    private static <T> T insideParentObservation(AnnotationConfigApplicationContext context, Supplier<T> call) {
        return Observation.createNotStarted("parent", context.getBean(ObservationRegistry.class))
                .observe(call);
    }

    private static Throwable failureOf(Future<?> future) {
        Throwable thrown = catchThrowable(() -> future.get(10, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOf(ExecutionException.class);
        return thrown.getCause();
    }

    private static @Nullable Throwable asyncTaskError(AnnotationConfigApplicationContext context) {
        List<Observation.Context> asyncTasks =
                context.getBean(RecordingObservations.class).startedNamed(AsyncTaskMarker.OBSERVATION_NAME);
        assertThat(asyncTasks).hasSize(1);
        return asyncTasks.getFirst().getError();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @Import(InstrumentedExecutorConfig.class)
    static class AsyncContext {

        @Bean
        Enrichment enrichment() {
            return new Enrichment();
        }

        @Bean
        Notifier notifier() {
            return new EmailNotifier();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerContext {

        @Bean
        static EventListenerObservationPostProcessor eventListenerObservationPostProcessor(
                ObjectProvider<ObservationRegistry> observationRegistry) {
            return new EventListenerObservationPostProcessor(observationRegistry::getObject);
        }

        @Bean
        Listeners.AsyncListener asyncListener() {
            return new Listeners.AsyncListener();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync(annotation = Offloaded.class)
    @Import(InstrumentedExecutorConfig.class)
    static class CustomAnnotationContext {

        @Bean
        OffloadedEnrichment offloadedEnrichment() {
            return new OffloadedEnrichment();
        }
    }

    /** Boot's arrangement: Peekaboot's decorator inside one that carries the caller's observation over. */
    @Configuration(proxyBeanMethods = false)
    static class InstrumentedExecutorConfig {

        @Bean
        static AsyncTaskFailurePostProcessor asyncTaskFailurePostProcessor() {
            return new AsyncTaskFailurePostProcessor();
        }

        @Bean
        RecordingObservations recordingObservations() {
            return new RecordingObservations();
        }

        @Bean
        ObservationRegistry observationRegistry(RecordingObservations observations) {
            return observations.registry();
        }

        @Bean
        ThreadPoolTaskExecutor taskExecutor(ObservationRegistry observationRegistry) {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setTaskDecorator(new CompositeTaskDecorator(List.<TaskDecorator>of(
                    new AsyncTaskDecorator(observationRegistry), new ContextPropagatingTaskDecorator())));
            return executor;
        }

        @Bean
        RecordingUncaughtHandler recordingUncaughtHandler() {
            return new RecordingUncaughtHandler();
        }

        @Bean
        AsyncConfigurer asyncConfigurer(RecordingUncaughtHandler handler) {
            return new AsyncConfigurer() {
                @Override
                public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                    return handler;
                }
            };
        }
    }

    static class RecordingUncaughtHandler implements AsyncUncaughtExceptionHandler {

        final List<Throwable> handled = new CopyOnWriteArrayList<>();
        private final CountDownLatch handledOnce = new CountDownLatch(1);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, @Nullable Object... params) {
            handled.add(ex);
            handledOnce.countDown();
        }

        boolean awaitHandled() throws InterruptedException {
            return handledOnce.await(10, TimeUnit.SECONDS);
        }
    }

    public static class Enrichment {

        @Async
        public void enrich() {
            throw rejected();
        }

        @Async
        public Future<String> enrichAsFuture() {
            throw rejected();
        }

        @Async
        public CompletableFuture<String> enrichAsCompletableFuture() {
            throw rejected();
        }

        @Async
        public CompletableFuture<String> returnFailedFuture() {
            return CompletableFuture.failedFuture(rejected());
        }

        @Async
        public CompletableFuture<String> succeed() {
            return CompletableFuture.completedFuture("enriched");
        }
    }

    public interface Notifier {

        CompletableFuture<String> notifyCustomer();
    }

    public static class EmailNotifier implements Notifier {

        @Async
        @Override
        public CompletableFuture<String> notifyCustomer() {
            throw rejected();
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface Offloaded {}

    public static class OffloadedEnrichment {

        @Offloaded
        public CompletableFuture<String> enrich() {
            throw rejected();
        }
    }
}
