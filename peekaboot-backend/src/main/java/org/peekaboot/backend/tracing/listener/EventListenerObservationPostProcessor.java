package org.peekaboot.backend.tracing.listener;

import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.util.function.SingletonSupplier;

/**
 * Puts {@link EventListenerObservationInterceptor} in front of every observable
 * {@code @EventListener} method, the way Spring's {@code @Async} support installs its own
 * interceptor, so neither the AOP starter nor AspectJ is needed. A decorating
 * {@code EventListenerFactory} cannot do this: Spring initialises only its own
 * {@code ApplicationListenerMethodAdapter}, through a package-private method.
 *
 * <p>Where the advisor sits decides what the span times. It goes in front of any advisor already
 * on the bean, so a listener's own transaction opens inside its span. And it runs one step ahead
 * of {@code AsyncAnnotationBeanPostProcessor} at that one's default order, which then puts the
 * async hand-off in front of this advisor, so an {@code @Async} listener's span opens on the
 * executor thread. An application that orders {@code @EnableAsync} below {@link #ORDER} gets a
 * span that times only the submission.
 *
 * <p>A bean marked {@code @Proxyable(INTERFACES)} opts back into a JDK proxy, so its listener
 * methods must then be declared on one of its interfaces.
 */
public final class EventListenerObservationPostProcessor extends AbstractBeanFactoryAwareAdvisingPostProcessor {

    /** One ahead of {@code @EnableAsync}'s default, {@code Ordered.LOWEST_PRECEDENCE}. */
    public static final int ORDER = LOWEST_PRECEDENCE - 1;

    /** Creates the post-processor; Boot configures the registry later, so it is resolved on the first listener call. */
    public EventListenerObservationPostProcessor(Supplier<ObservationRegistry> observationRegistry) {
        this.advisor = new DefaultPointcutAdvisor(
                new EventListenerPointcut(),
                new EventListenerObservationInterceptor(SingletonSupplier.of(observationRegistry)));
        setBeforeExistingAdvisors(true);
        // a JDK proxy exposes only interface methods, and a listener method rarely is one
        setProxyTargetClass(true);
        setOrder(ORDER);
    }
}
