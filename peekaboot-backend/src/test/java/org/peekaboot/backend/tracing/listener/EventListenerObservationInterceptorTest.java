package org.peekaboot.backend.tracing.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.example.listener.Listeners;
import io.micrometer.observation.Observation;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.EventListenerMarker;
import org.peekaboot.backend.testsupport.RecordingObservations;
import org.springframework.aop.framework.ProxyFactory;

class EventListenerObservationInterceptorTest {

    private final RecordingObservations observations = new RecordingObservations();

    @Test
    void observesAListenerCalledWithAnObservationInScope() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        observations.insideParentObservation(() -> listener.onOrderPlaced(new Listeners.OrderPlaced("PK-1")));

        assertThat(listenerContexts()).singleElement().satisfies(context -> {
            assertThat(context.getContextualName()).isEqualTo("Plain#onOrderPlaced");
            assertThat(context.getParentObservation().getContextView().getName())
                    .isEqualTo("parent");
        });
    }

    @Test
    void makesTheListenerObservationCurrentWhileTheListenerRuns() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        observations.insideParentObservation(() -> listener.onOrderPlaced(new Listeners.OrderPlaced("PK-1")));

        assertThat(observations.scopedNamed(EventListenerMarker.OBSERVATION_NAME))
                .isEqualTo(listenerContexts());
        assertThat(listenerContexts()).hasSize(1);
    }

    @Test
    void tagsTheEventTypeAndTheListener() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        observations.insideParentObservation(() -> listener.onOrderPlaced(new Listeners.OrderPlaced("PK-1")));

        Observation.Context context = listenerContexts().getFirst();
        assertThat(tag(context, EventListenerMarker.EVENT_TYPE_TAG_KEY)).isEqualTo("OrderPlaced");
        assertThat(tag(context, EventListenerMarker.LISTENER_CLASS_TAG_KEY))
                .isEqualTo("com.example.listener.Listeners$Plain");
        assertThat(tag(context, EventListenerMarker.LISTENER_METHOD_TAG_KEY)).isEqualTo("onOrderPlaced");
    }

    /** The listener is the bean that heard the event, not the class that happens to declare the method. */
    @Test
    void namesTheBeanClassForAnInheritedListenerMethod() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.InheritedPlain());

        observations.insideParentObservation(() -> listener.onOrderPlaced(new Listeners.OrderPlaced("PK-1")));

        Observation.Context context = listenerContexts().getFirst();
        assertThat(context.getContextualName()).isEqualTo("InheritedPlain#onOrderPlaced");
        assertThat(tag(context, EventListenerMarker.LISTENER_CLASS_TAG_KEY))
                .isEqualTo("com.example.listener.Listeners$InheritedPlain");
    }

    @Test
    void namesTheEventTypeOfAParameterlessListenerFromItsAnnotation() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        observations.insideParentObservation(() -> {
            listener.onAnyOrder();
            return null;
        });

        assertThat(tag(listenerContexts().getFirst(), EventListenerMarker.EVENT_TYPE_TAG_KEY))
                .isEqualTo("OrderPlaced");
    }

    @Test
    void prefersTheEventClassesOnTheAnnotationToTheDeclaredParameterType() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        observations.insideParentObservation(() -> {
            listener.onAnyEvent(new Listeners.OrderPlaced("PK-1"));
            return null;
        });

        assertThat(tag(listenerContexts().getFirst(), EventListenerMarker.EVENT_TYPE_TAG_KEY))
                .isEqualTo("OrderPlaced");
    }

    @Test
    void joinsEveryEventClassOnTheAnnotation() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        observations.insideParentObservation(() -> {
            listener.onEitherOrder();
            return null;
        });

        assertThat(tag(listenerContexts().getFirst(), EventListenerMarker.EVENT_TYPE_TAG_KEY))
                .isEqualTo("OrderPlaced,RejectedOrder");
    }

    @Test
    void namesTheEventTypeOfAParameterlessListenerDeclaredThroughAMetaAnnotation() throws Exception {
        Listeners.AfterCommit listener = intercepted(new Listeners.AfterCommit());

        observations.insideParentObservation(() -> {
            listener.onAnyRejection();
            return null;
        });

        assertThat(tag(listenerContexts().getFirst(), EventListenerMarker.EVENT_TYPE_TAG_KEY))
                .isEqualTo("RejectedOrder");
    }

    /** Spring publishes a listener's return value as a follow-up event, so it must survive the span. */
    @Test
    void returnsWhatTheListenerReturned() throws Exception {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        String result =
                observations.insideParentObservation(() -> listener.onOrderPlaced(new Listeners.OrderPlaced("PK-1")));

        assertThat(result).isEqualTo("confirmed PK-1");
    }

    /** With nothing in scope there is no trace to attribute the work to, and a span would start a rootless one. */
    @Test
    void runsTheListenerWithoutObservingItWhenNoObservationIsInScope() {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        String result = listener.onOrderPlaced(new Listeners.OrderPlaced("PK-1"));

        assertThat(result).isEqualTo("confirmed PK-1");
        assertThat(listenerContexts()).isEmpty();
    }

    @Test
    void recordsAThrownErrorOnTheObservationAndRethrowsIt() {
        Listeners.Plain listener = intercepted(new Listeners.Plain());

        Throwable thrown = catchThrowable(() -> observations.insideParentObservation(() -> {
            listener.onRejectedOrder(new Listeners.RejectedOrder("out of stock"));
            return null;
        }));

        assertThat(thrown).isInstanceOf(IOException.class).hasMessage("out of stock");
        assertThat(listenerContexts())
                .singleElement()
                .satisfies(context -> assertThat(context.getError()).isSameAs(thrown));
    }

    private <T> T intercepted(T target) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new EventListenerObservationInterceptor(observations::registry));
        @SuppressWarnings("unchecked")
        T proxy = (T) proxyFactory.getProxy();
        return proxy;
    }

    private List<Observation.Context> listenerContexts() {
        return observations.startedNamed(EventListenerMarker.OBSERVATION_NAME);
    }

    private static String tag(Observation.Context context, String key) {
        return context.getLowCardinalityKeyValue(key).getValue();
    }
}
