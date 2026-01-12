package net.osslabz.peekaboot.tracing.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class InMemoryTraceEventBus implements TraceEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTraceEventBus.class);

    private final List<Consumer<TraceDataEvent>> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void publish(TraceDataEvent event) {
        if (event == null) {
            return;
        }
        for (Consumer<TraceDataEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Event listener failed for traceId={}: {}", event.traceId(), e.getMessage());
            }
        }
    }

    @Override
    public void subscribe(Consumer<TraceDataEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }
}
