package org.peekaboot.backend.tracing.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.listener.Listeners;
import org.junit.jupiter.api.Test;

class EventListenerPointcutTest {

    private final EventListenerPointcut pointcut = new EventListenerPointcut();

    /** A JDK proxy passes the interface's method while the annotation sits on the implementation's. */
    @Test
    void matchesAnInterfaceMethodWhoseImplementationIsTheListener() throws Exception {
        assertThat(pointcut.matches(
                        Listeners.OrderNotifier.class.getMethod("notifyCustomer", Listeners.OrderPlaced.class),
                        Listeners.AnnotatedNotifier.class))
                .isTrue();
    }

    @Test
    void doesNotMatchAnInterfaceMethodWhoseImplementationIsNoListener() throws Exception {
        assertThat(pointcut.matches(
                        Listeners.OrderNotifier.class.getMethod("notifyCustomer", Listeners.OrderPlaced.class),
                        Listeners.NotifyingListener.class))
                .isFalse();
    }
}
