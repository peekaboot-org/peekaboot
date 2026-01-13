package net.osslabz.peekaboot.backend.tracing.event;

import java.util.function.Consumer;

public interface TraceEventBus {
    void publish(TraceDataEvent event);

    void subscribe(Consumer<TraceDataEvent> listener);
}
