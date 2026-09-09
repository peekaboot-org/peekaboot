package org.peekaboot.testingapp.integration;

import io.micrometer.tracing.Tracer;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Shared test configuration for dev toolbar integration tests. Supplies a deterministic
 * {@link Tracer} and the {@link TraceStore} the tests assert on; the toolbar-injection
 * beans themselves (ToolbarDataProvider, the devToolbarFilter registration) and the store's
 * event listener come from the real auto-configuration, because these tests boot the real
 * sample app.
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
        return new LogFreeTraceStore();
    }

    @Bean
    Tracer testTracer() {
        return new DeterministicTracer(FIXED_TRACE_ID, FIXED_SPAN_ID);
    }

    /**
     * Keeps no captured logs. Logback's {@code LoggerContext} is JVM-wide while the capture
     * appender is per application context, so this context's appender also sees the log
     * events of every context running beside it - without this, a request served by a
     * concurrently running IT's app lands here as a log-only trace and breaks
     * {@link DashboardTraceViewIT}'s trace counts. Log capture itself is covered by
     * {@code LogCaptureIT}, which boots a context of its own.
     */
    private static final class LogFreeTraceStore extends InMemoryTraceStore {

        LogFreeTraceStore() {
            super(new PeekabootTracingProperties());
        }

        @Override
        public void addLog(LogCapturedEvent log) {
            // dropped on purpose, see the class comment
        }
    }
}
