package com.example.listener;

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/** Listener beans for the instrumentation tests, outside every package it skips, so they stand for an application's. */
public final class Listeners {

    private Listeners() {}

    public record OrderPlaced(String reference) {}

    public record RejectedOrder(String reason) {}

    public static class Plain {

        @EventListener
        public String onOrderPlaced(OrderPlaced event) {
            return "confirmed " + event.reference();
        }

        @EventListener(OrderPlaced.class)
        public void onAnyOrder() {}

        @EventListener
        public void onRejectedOrder(RejectedOrder event) throws IOException {
            throw new IOException(event.reason());
        }

        @EventListener(classes = OrderPlaced.class)
        public void onAnyEvent(Object event) {}

        @EventListener({OrderPlaced.class, RejectedOrder.class})
        public void onEitherOrder() {}
    }

    public static class InheritedPlain extends Plain {}

    public static class AfterCommit {

        @TransactionalEventListener
        public void onOrderPlaced(OrderPlaced event) {}

        @TransactionalEventListener(classes = RejectedOrder.class, fallbackExecution = true)
        public void onAnyRejection() {}
    }

    public static class NoListener {

        public void onOrderPlaced(OrderPlaced event) {}
    }

    public static class PartlyObserved {

        @Observed
        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}

        @EventListener
        public void onRejectedOrder(RejectedOrder event) {}
    }

    @Observed
    public static class ObservedClass {

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}
    }

    public static class StaticListener {

        @EventListener
        public static void onOrderPlaced(OrderPlaced event) {}
    }

    public static final class FinalClass {

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}
    }

    public static class FinalMethod {

        @EventListener
        public final void onOrderPlaced(OrderPlaced event) {}
    }

    public static class FinalAccessor {

        private final String reference;

        public FinalAccessor(String reference) {
            this.reference = reference;
        }

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}

        public final String reference() {
            return reference;
        }
    }

    public static class InheritedFinalAccessor extends FinalAccessor {

        public InheritedFinalAccessor(String reference) {
            super(reference);
        }
    }

    public static class PrivateAndStaticFinalMethods {

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {
            describe(event);
        }

        public static final String channel() {
            return "email";
        }

        private final String describe(OrderPlaced event) {
            return channel() + " " + event.reference();
        }
    }

    public static class PrivateMethod {

        private final List<Object> heard = new CopyOnWriteArrayList<>();

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {
            heard.add(event);
        }

        @SuppressWarnings("UnusedMethod") // Spring's event multicaster calls it reflectively
        @EventListener
        private void onRejectedOrder(RejectedOrder event) {
            heard.add(event);
        }

        public List<Object> heard() {
            return heard;
        }
    }

    public static class RequiresNew {

        @TransactionalEventListener(fallbackExecution = true)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public void onOrderPlaced(OrderPlaced event) {}
    }

    public static class AsyncListener {

        private final CountDownLatch handled = new CountDownLatch(1);

        @Async
        @EventListener
        public void onOrderPlaced(OrderPlaced event) {
            handled.countDown();
        }

        public boolean awaitHandled() throws InterruptedException {
            return handled.await(10, TimeUnit.SECONDS);
        }
    }

    public static sealed class SealedClass permits SealedClass.Permitted {

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}

        public static final class Permitted extends SealedClass {}
    }

    /** A constant with a body makes the enum implicitly sealed. */
    public enum Channel {
        EMAIL,
        SMS {
            @Override
            public String label() {
                return "text";
            }
        };

        public String label() {
            return name();
        }

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}
    }

    public static class PrivateConstructor {

        private PrivateConstructor() {}

        public static PrivateConstructor create() {
            return new PrivateConstructor();
        }

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}
    }

    public interface OrderNotifier {

        void notifyCustomer(OrderPlaced event);
    }

    public static class NotifyingListener implements OrderNotifier {

        @Override
        public void notifyCustomer(OrderPlaced event) {}

        @EventListener
        public void onOrderPlaced(OrderPlaced event) {}
    }

    public static class AnnotatedNotifier implements OrderNotifier {

        @Override
        @EventListener
        public void notifyCustomer(OrderPlaced event) {}
    }
}
