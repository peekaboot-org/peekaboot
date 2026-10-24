package org.peekaboot.backend.mapper.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;
import static org.peekaboot.backend.testsupport.Spans.span;

import io.micrometer.tracing.Span;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.domain.trace.TraceTabSummary;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceFolding;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceDataBundle;

class TraceLogMapperTest {

    private final TraceLogMapper mapper =
            new TraceLogMapper(new MaskingEngine(), new StackTraceRules(List.of(), List.of()));
    private InMemoryTraceStore store;

    @BeforeEach
    void setUp() {
        store = TraceStores.withDefaults();
        store.addSpan(span("root")
                .in("t1")
                .named("GET /x")
                .kind(Span.Kind.SERVER)
                .at(0, 100)
                .build());
    }

    @Test
    void aLogCarriesWhatWasCaptured() {
        store.addLog(log("t1").inSpan("root").at("WARN").saying("careful").build());

        TraceLog result = mapped(mapper).getFirst();

        assertThat(result.spanId()).isEqualTo("root");
        assertThat(result.level()).isEqualTo("WARN");
        assertThat(result.message()).isEqualTo("careful");
    }

    @Test
    void aLogWithoutAThrowableCarriesNoStackTraceOrRanges() {
        store.addLog(log("t1").inSpan("root").saying("no trouble here").build());

        TraceLog result = mapped(mapper).getFirst();

        assertThat(result.stackTrace()).isNull();
        assertThat(result.hiddenFrames()).isEmpty();
        assertThat(result.applicationFrames()).isEmpty();
    }

    /**
     * The mapper folds with the rules it is given, the same way the error page does; this pins
     * that wiring rather than {@link StackTraceFolding}'s own folding rules.
     *
     * <p>The fixture's first line ends in a lone {@code \r}, which {@code String.lines()} (what
     * folding splits on) treats as a line terminator but JavaScript's {@code split('\n')} (what
     * the browser splits on) does not - and the expected value is written out literally rather
     * than re-deriving it with the same {@code String.join("\n", ...)} expression the
     * implementation runs, so this pins the stated reason for that join instead of restating it.
     */
    @Test
    void aLogWithAThrowableCarriesItsFoldedStackTrace() {
        String trace = "java.lang.IllegalStateException: boom\r"
                + "\tat org.peekaboot.app.Thing.method(Thing.java:10)\n"
                + "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)\n";
        store.addLog(log("t1").inSpan("root").withStackTrace(trace).build());

        TraceLog result = mapped(folding()).getFirst();

        assertThat(result.stackTrace()).isEqualTo("""
                        java.lang.IllegalStateException: boom
                        \tat org.peekaboot.app.Thing.method(Thing.java:10)
                        \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)""");
        assertThat(result.applicationFrames()).containsExactly(new StackTraceFolding.Range(1, 2));
        assertThat(result.hiddenFrames()).containsExactly(new StackTraceFolding.Range(2, 3));
    }

    /** A log line can echo the failing request's URL, credentials and all, as a span's errorMessage can. */
    @Test
    void aLogsMessageAndStackTraceReachTheWireMasked() {
        String trace = """
                java.lang.IllegalStateException: GET https://admin:hunter2@example.com/api failed
                \tat org.peekaboot.app.Thing.method(Thing.java:10)
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)
                """;
        store.addLog(log("t1")
                .inSpan("root")
                .saying("calling https://admin:hunter2@example.com/api")
                .withStackTrace(trace)
                .build());

        TraceLog result = mapped(folding()).getFirst();

        assertThat(result.message()).isEqualTo("calling https://******@example.com/api");
        assertThat(result.stackTrace()).isEqualTo("""
                        java.lang.IllegalStateException: GET https://******@example.com/api failed
                        \tat org.peekaboot.app.Thing.method(Thing.java:10)
                        \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)""");
        assertThat(result.applicationFrames()).containsExactly(new StackTraceFolding.Range(1, 2));
        assertThat(result.hiddenFrames()).containsExactly(new StackTraceFolding.Range(2, 3));
    }

    /** Masked before folding: a secret spanning lines matches only in the whole trace, never line by line. */
    @Test
    void aPrivateKeySpanningStackTraceLinesIsMaskedWhole() {
        String trace = """
                java.lang.IllegalStateException: bad key -----BEGIN PRIVATE KEY-----
                MIIEvQIBADANBgkqhkiG9w0BAQEFAASC
                -----END PRIVATE KEY-----
                \tat org.peekaboot.app.Thing.method(Thing.java:10)
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)
                """;
        store.addLog(log("t1").inSpan("root").withStackTrace(trace).build());

        TraceLog result = mapped(folding()).getFirst();

        assertThat(result.stackTrace()).isEqualTo("""
                        java.lang.IllegalStateException: bad key ******
                        \tat org.peekaboot.app.Thing.method(Thing.java:10)
                        \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1)""");
        assertThat(result.applicationFrames()).containsExactly(new StackTraceFolding.Range(1, 2));
        assertThat(result.hiddenFrames()).containsExactly(new StackTraceFolding.Range(2, 3));
    }

    @Test
    void aLogWithoutASpanIdNamesNoSpan() {
        store.addLog(log("t1").inSpan(null).saying("spanless").build());

        assertThat(mapped(mapper)).extracting(TraceLog::spanId).containsOnlyNulls();
    }

    @Test
    void aLogEmittedInAGrandchildSpanNamesThatSpan() {
        store.addSpan(span("child")
                .in("t1")
                .parent("root")
                .named("service")
                .at(10, 50)
                .build());
        store.addSpan(span("grandchild")
                .in("t1")
                .parent("child")
                .named("repository")
                .at(20, 20)
                .build());
        store.addLog(log("t1").inSpan("grandchild").saying("deep").build());

        assertThat(mapped(mapper)).extracting(TraceLog::spanId).containsExactly("grandchild");
    }

    /** A log emitted inside a duplicate's MDC scope carries the duplicate's id, which folding removed. */
    @Test
    void aLogEmittedInAFoldedDuplicateSpanNamesTheSurvivingSpan() {
        store.addSpan(jdbcDuplicate("db-dup", "db", "SELECT 1").in("t1").build());
        store.addSpan(jdbcQuery("db", "SELECT 1").in("t1").parent("root").build());
        store.addLog(log("t1").inSpan("db-dup").saying("Datasource log").build());

        assertThat(mapped(mapper)).extracting(TraceLog::spanId).containsExactly("db");
    }

    @Test
    void aSubtreeHoldsOnlyTheLogsEmittedInsideItsOwnSpans() {
        store.addSpan(
                span("async").in("t1").parent("root").named("task").at(50, 500).build());
        store.addSpan(span("async-child")
                .in("t1")
                .parent("async")
                .named("work")
                .at(60, 10)
                .build());
        store.addLog(log("t1").inSpan("root").saying("outside").build());
        store.addLog(log("t1").inSpan("async-child").saying("inside").build());
        store.addLog(log("t1").inSpan(null).saying("nowhere").build());
        TraceDataBundle bundle = bundle();
        TraceTree subtree =
                new TraceTreeMapper(new MaskingEngine(), SqlFormatter.NONE).mapSubtree(bundle.snapshot(), "async");

        assertThat(mapper.logsWithin(subtree.rootSpan(), bundle))
                .extracting(LogCapturedEvent::message)
                .containsExactly("inside");
    }

    @Test
    void theLogsSummaryCountsLogsByLevel() {
        for (String level : List.of("ERROR", "WARN", "WARN", "INFO", "DEBUG")) {
            store.addLog(log("t1").inSpan("root").at(level).build());
        }
        TraceDataBundle bundle = bundle();
        TraceTree tree = new TraceTreeMapper(new MaskingEngine(), SqlFormatter.NONE).map(bundle.snapshot());

        TraceTree result = mapper.withLogsSummary(tree, bundle.logs());

        assertThat(result.summary().logs()).isEqualTo(new TraceTabSummary.LogsSummary(5, 1, 2));
        assertThat(result.summary().spans()).isEqualTo(tree.summary().spans());
    }

    private static TraceLogMapper folding() {
        return new TraceLogMapper(
                new MaskingEngine(), new StackTraceRules(List.of("org.springframework"), List.of("org.peekaboot.app")));
    }

    private TraceDataBundle bundle() {
        return store.getTrace("t1").orElseThrow();
    }

    private List<TraceLog> mapped(TraceLogMapper logMapper) {
        TraceDataBundle bundle = bundle();
        return logMapper.map(bundle, bundle.logs());
    }
}
