package org.peekaboot.backend.tracing.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.span;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.RequestCompletedEvents;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.event.RequestCompletedEvent;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.event.SpanDataEvent;
import org.peekaboot.backend.tracing.event.TraceDiscardedEvent;
import org.springframework.context.support.StaticApplicationContext;

class CaptureEventPublisherTest {

    private final List<TraceInput> handedOn = new ArrayList<>();
    private final CaptureEventPublisher publisher = new CaptureEventPublisher(
            new TraceStoreEventListener(handedOn::add, TraceStores.withDefaults(), new StaticApplicationContext()));

    @Test
    void handsEachCaptureEventToTheStoresListener() {
        SpanData spanData = span("span1").build();
        LogCapturedEvent logged = log("trace1").build();
        RequestCompletedEvent request = RequestCompletedEvents.minimal("trace1");

        publisher.publishEvent(new SpanDataEvent(spanData));
        publisher.publishEvent(logged);
        publisher.publishEvent(request);
        publisher.publishEvent(new TraceDiscardedEvent("trace1", null));

        assertThat(handedOn)
                .containsExactly(
                        new TraceInput.SpanArrived(spanData),
                        new TraceInput.LogCaptured(logged),
                        new TraceInput.RequestCompleted(request),
                        new TraceInput.TraceDiscarded("trace1"));
    }

    /** Anything else would be an application's event, which belongs on the application's own publisher. */
    @Test
    void refusesAnEventThatIsNoCaptureEvent() {
        assertThatThrownBy(() -> publisher.publishEvent("x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not a capture event: java.lang.String");
    }
}
