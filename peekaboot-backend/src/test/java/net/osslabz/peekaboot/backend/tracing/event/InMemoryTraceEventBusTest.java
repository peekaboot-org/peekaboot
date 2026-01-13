package net.osslabz.peekaboot.backend.tracing.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTraceEventBusTest {

    private InMemoryTraceEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryTraceEventBus();
    }

    @Test
    void publish_notifiesSubscriber() {
        List<TraceDataEvent> received = new ArrayList<>();
        eventBus.subscribe(received::add);

        var event = new SpanCompletedEvent("trace1", "span1", null, "test", null, 0, 100, Map.of(), null, null);
        eventBus.publish(event);

        assertThat(received).containsExactly(event);
    }

    @Test
    void publish_notifiesMultipleSubscribers() {
        List<TraceDataEvent> received1 = new ArrayList<>();
        List<TraceDataEvent> received2 = new ArrayList<>();
        eventBus.subscribe(received1::add);
        eventBus.subscribe(received2::add);

        var event = new SpanCompletedEvent("trace1", "span1", null, "test", null, 0, 100, Map.of(), null, null);
        eventBus.publish(event);

        assertThat(received1).containsExactly(event);
        assertThat(received2).containsExactly(event);
    }

    @Test
    void publish_handlesSubscriberException() {
        List<TraceDataEvent> received = new ArrayList<>();
        eventBus.subscribe(e -> {
            throw new RuntimeException("test");
        });
        eventBus.subscribe(received::add);

        var event = new SpanCompletedEvent("trace1", "span1", null, "test", null, 0, 100, Map.of(), null, null);
        eventBus.publish(event);

        assertThat(received).containsExactly(event);
    }

    @Test
    void publish_ignoresNullEvent() {
        List<TraceDataEvent> received = new ArrayList<>();
        eventBus.subscribe(received::add);

        eventBus.publish(null);

        assertThat(received).isEmpty();
    }

    @Test
    void subscribe_ignoresNullListener() {
        eventBus.subscribe(null);

        var event = new SpanCompletedEvent("trace1", "span1", null, "test", null, 0, 100, Map.of(), null, null);
        eventBus.publish(event);
        // Should not throw
    }
}
