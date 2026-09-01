package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Tracer;
import java.time.Duration;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.peekaboot.backend.tracing.store.TraceStoreEventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared test configuration for dev toolbar integration tests. Supplies a deterministic
 * {@link Tracer} and a small-capacity {@link TraceStore}; the toolbar-injection beans
 * themselves (ToolbarDataProvider, the devToolbarFilter registration) come from the real
 * {@code DevToolbarAutoConfiguration}, because these tests boot the real sample app.
 *
 * <p>A stand-in {@code Tracer} is used rather than the real, auto-configured one:
 * {@link DashboardTraceViewIT}'s exact trace-count assertions need the
 * {@code TraceStore} to contain only what the test put there, and the real tracer
 * would also capture the app's own spans (e.g. the JDBC calls each test's
 * {@code setUp()} makes) into the same store.
 */
@TestConfiguration
public class SharedToolbarTestConfig {

    /** The one trace id every toolbar rendered under this configuration reports. */
    static final String FIXED_TRACE_ID = "cafebabecafebabecafebabecafebabe";

    private static final String FIXED_SPAN_ID = "deadbeefdeadbeef";

    @Bean
    TraceStore traceStore() {
        return new InMemoryTraceStore(100, 50, Duration.ofMinutes(5));
    }

    @Bean
    TraceStoreEventListener traceStoreEventListener(TraceStore traceStore) {
        return new TraceStoreEventListener(traceStore);
    }

    @Bean
    @ConditionalOnMissingBean
    Tracer testTracer() {
        return new DeterministicTracer(FIXED_TRACE_ID, FIXED_SPAN_ID);
    }
}
