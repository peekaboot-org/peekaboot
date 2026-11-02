package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.testsupport.LogSearches.search;
import static org.peekaboot.backend.testsupport.Logs.log;
import static org.peekaboot.backend.testsupport.Logs.untraced;
import static org.peekaboot.backend.testsupport.Spans.jdbcDuplicate;
import static org.peekaboot.backend.testsupport.Spans.jdbcQuery;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.domain.log.LogSearchResponse;
import org.peekaboot.backend.domain.trace.TraceLog;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.testsupport.LogSearches;
import org.peekaboot.backend.testsupport.TraceStores;
import org.peekaboot.backend.tracing.store.InMemoryTraceStore;
import org.peekaboot.backend.tracing.store.TraceStore;

class LogSearchServiceTest {

    private static final String SQL = "SELECT * FROM person";

    private final InMemoryTraceStore store = TraceStores.withDefaults();

    private final LogSearchService service = newService(store);

    private static LogSearchService newService(TraceStore store) {
        return new LogSearchService(store, new MaskingEngine(), new StackTraceRules(List.of(), List.of()));
    }

    private List<String> messages(LogSearches.Builder search) {
        return service.search(search.build()).logs().stream()
                .map(TraceLog::message)
                .toList();
    }

    /** Every line has the same timestamp, so only the store's arrival order can put them in this order. */
    @Test
    void listsTracedAndUntracedLinesNewestFirstAsTheyArrived() {
        store.addLog(untraced().saying("a").build());
        store.addLog(log("t1").saying("b").build());
        store.addLog(untraced().saying("c").build());
        store.addLog(log("t2").saying("d").build());

        assertThat(service.search(search().build()).logs())
                .extracting(TraceLog::message, TraceLog::traceId)
                .containsExactly(tuple("d", "t2"), tuple("c", null), tuple("b", "t1"), tuple("a", null));
    }

    @Test
    void anUntracedLineNamesNoTraceOrSpanButItsLoggerAndThread() {
        store.addLog(untraced()
                .from("com.acme.Startup")
                .onThread("restartedMain")
                .saying("started")
                .build());

        TraceLog line = service.search(search().build()).logs().getFirst();

        assertThat(line.traceId()).isNull();
        assertThat(line.spanId()).isNull();
        assertThat(line.logger()).isEqualTo("com.acme.Startup");
        assertThat(line.thread()).isEqualTo("restartedMain");
    }

    @Test
    void keepsTheLevelAndEveryOneAbove() {
        for (String level : List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR")) {
            store.addLog(untraced().at(level).saying(level).build());
        }

        assertThat(messages(search().level("warn"))).containsExactly("ERROR", "WARN");
        assertThat(messages(search().level("INFO"))).containsExactly("ERROR", "WARN", "INFO");
    }

    /** {@code q} is no pattern: a parenthesis and an asterisk match themselves, in the message or the stack trace. */
    @Test
    void qIsALiteralSubstringInAnyCase() {
        store.addLog(untraced().saying("Order (boom) failed").build());
        store.addLog(untraced().saying("order boom").build());
        store.addLog(untraced().saying("nothing here").withStackTrace("""
                        java.lang.IllegalStateException: x
                        \tat com.acme.Gateway.call(Gateway.java:7)
                        Caused by: com.acme.GatewayTimeout: slow
                        """).build());

        assertThat(messages(search().q("(BOOM"))).containsExactly("Order (boom) failed");
        assertThat(messages(search().q("gatewaytimeout"))).containsExactly("nothing here");
        assertThat(messages(search().q("*"))).isEmpty();
    }

    /** The masking rules replace the credentials before {@code q} reads the line. */
    @Test
    void qFindsTheMaskNeverTheSecret() {
        store.addLog(untraced()
                .saying("calling https://agent:hunter2secret@partner.example")
                .build());

        assertThat(messages(search().q("hunter2secret"))).isEmpty();
        assertThat(messages(search().q("******"))).containsExactly("calling https://******@partner.example");
    }

    /** The appender captures a null message as an empty one. */
    @Test
    void aLineWithAnEmptyMessageIsListedAndMatchesNoText() {
        store.addLog(untraced().saying("").build());
        store.addLog(untraced().saying("present").build());

        assertThat(messages(search())).containsExactly("present", "");
        assertThat(messages(search().q("p"))).containsExactly("present");
    }

    /** A prefix of the name as written: a package's sibling with a longer name matches too, another case does not. */
    @Test
    void filtersByTheStartOfTheLoggersName() {
        store.addLog(untraced()
                .from("org.acme.orders.OrderService")
                .saying("service")
                .build());
        store.addLog(
                untraced().from("org.acme.ordersarchive.Job").saying("archive").build());
        store.addLog(untraced().from("com.other.Thing").saying("other").build());

        assertThat(messages(search().logger("org.acme.orders"))).containsExactly("archive", "service");
        assertThat(messages(search().logger("ORG.acme"))).isEmpty();
    }

    @Test
    void searchesOneTraceByItsId() {
        store.addLog(log("t1").saying("one").build());
        store.addLog(log("t2").saying("two").build());
        store.addLog(untraced().saying("none").build());
        store.addLog(log("t1").saying("three").build());

        assertThat(messages(search().traceId("t1"))).containsExactly("three", "one");
        assertThat(messages(search().traceId("unknown"))).isEmpty();
    }

    @Test
    void filtersByTimestampHalfOpen() {
        for (int second = 0; second <= 3; second++) {
            store.addLog(untraced()
                    .timestamp(Instant.EPOCH.plusSeconds(second))
                    .saying("at" + second)
                    .build());
        }

        assertThat(messages(search().since("1970-01-01T00:00:01Z").until("1970-01-01T00:00:03Z")))
                .containsExactly("at2", "at1");
    }

    @Test
    void pagesWithoutRepeatingOrSkippingWhileLinesArrive() {
        for (int i = 1; i <= 5; i++) {
            store.addLog(untraced().saying("line" + i).build());
        }

        LogSearchResponse first = service.search(search().limit(2).build());
        store.addLog(untraced().saying("line6").build());
        LogSearchResponse second =
                service.search(search().limit(2).cursor(first.next()).build());
        LogSearchResponse third =
                service.search(search().limit(2).cursor(second.next()).build());

        assertThat(first.logs()).extracting(TraceLog::message).containsExactly("line5", "line4");
        assertThat(second.logs()).extracting(TraceLog::message).containsExactly("line3", "line2");
        assertThat(third.logs()).extracting(TraceLog::message).containsExactly("line1");
        assertThat(third.next()).isNull();
    }

    @Test
    void aFullLastPageHasNoNext() {
        store.addLog(untraced().saying("a").build());
        store.addLog(untraced().saying("b").build());

        assertThat(service.search(search().limit(2).build()).next()).isNull();
    }

    /** Only an older match makes a {@code next}. The oldest line is one {@code q} skips, so it makes no page. */
    @Test
    void nextLooksForAnotherMatchNotAnotherLine() {
        store.addLog(untraced().saying("other").build());
        store.addLog(untraced().saying("match 1").build());
        store.addLog(untraced().saying("other").build());
        store.addLog(untraced().saying("match 2").build());
        store.addLog(untraced().saying("other").build());

        LogSearchResponse page = service.search(search().q("match").limit(1).build());
        LogSearchResponse rest =
                service.search(search().q("match").limit(1).cursor(page.next()).build());

        assertThat(page.logs()).extracting(TraceLog::message).containsExactly("match 2");
        assertThat(rest.logs()).extracting(TraceLog::message).containsExactly("match 1");
        assertThat(rest.next()).isNull();
    }

    /** A line names the span that survived folding, as it does in its own trace. */
    @Test
    void aTracedLineNamesTheSpanThatSurvivedFolding() {
        store.addSpan(jdbcDuplicate("dup", "real", SQL).in("t1").build());
        store.addSpan(jdbcQuery("real", SQL).in("t1").build());
        store.addLog(log("t1").inSpan("dup").saying("queried").build());

        assertThat(service.search(search().build()).logs().getFirst().spanId()).isEqualTo("real");
    }

    @Test
    void searchesNothingWhileTracingIsOffButStillReadsItsParameters() {
        LogSearchService off = newService(null);

        assertThat(off.search(search().build()).logs()).isEmpty();
        assertThatThrownBy(() -> off.search(search().level("loud").build()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("level");
                    assertThat(e.problem().detail())
                            .isEqualTo("Unknown level 'loud'. Accepted: TRACE, DEBUG, INFO, WARN, ERROR.");
                });
    }

    @Test
    void aCursorFromAnotherNumberingIsRefused() {
        store.addLog(log("t1").saying("one").build());
        store.addLog(log("t1").saying("two").build());
        String next = service.search(search().limit(1).build()).next();
        InMemoryTraceStore restarted = TraceStores.withDefaults();
        restarted.addLog(log("t1").saying("one").build());
        restarted.addLog(log("t1").saying("two").build());

        assertThatThrownBy(() -> newService(restarted)
                        .search(search().limit(1).cursor(next).build()))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("cursor");
                    assertThat(e.problem().detail()).contains("before the application restarted");
                });
    }

    /** With tracing off there is no numbering to compare, so a cursor of the right shape asks for an empty page. */
    @Test
    void aWellFormedCursorWithTracingOffFindsNothing() {
        LogSearchResponse response =
                newService(null).search(search().cursor("bC43LjU").build());

        assertThat(response.logs()).isEmpty();
        assertThat(response.next()).isNull();
    }

    /** {@code dC43LjEuMS4tMQ} is a trace listing cursor. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "limit | 0",
                "limit | 10001",
                "since | yesterday",
                "until | 1970-13-01T00:00:00Z",
                "cursor | dC43LjEuMS4tMQ",
                "cursor | nope!"
            })
    void refusesABadParameterNamingIt(String parameter, String value) {
        LogSearches.Builder search =
                switch (parameter) {
                    case "limit" -> search().limit(value);
                    case "since" -> search().since(value);
                    case "until" -> search().until(value);
                    default -> search().cursor(value);
                };

        assertThatThrownBy(() -> service.search(search.build()))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo(parameter));
    }
}
