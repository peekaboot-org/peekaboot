package net.osslabz.peekaboot.tracing.event;

import java.util.function.Consumer;

public interface TraceEventBus {
    void publish(TraceDataEvent event);

    void subscribe(Consumer<TraceDataEvent> listener);
}
