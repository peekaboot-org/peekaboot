package net.osslabz.peekaboot.backend.service;

import net.osslabz.peekaboot.backend.mapper.trace.QueryExtractor;
import net.osslabz.peekaboot.backend.tracing.query.TraceQueryService;
import net.osslabz.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRawServiceTest {

    @Test
    void tracingAvailableWhenQueryServicePresent() {
        InMemoryTraceStore storage = new InMemoryTraceStore(10, 10, Duration.ofMinutes(1));
        TraceRawService service = new TraceRawService(new TraceQueryService(storage), storage, new QueryExtractor());

        assertThat(service.isTracingAvailable()).isTrue();
    }

    @Test
    void tracingUnavailableWithoutQueryService() {
        TraceRawService service = new TraceRawService(null, null, new QueryExtractor());

        assertThat(service.isTracingAvailable()).isFalse();
    }
}
