package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.peekaboot.backend.domain.trace.AsyncTaskMarker;
import org.peekaboot.backend.domain.trace.EventListenerMarker;
import org.peekaboot.testingapp.TestingApp;
import org.peekaboot.testingapp.order.NewOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** What Peekaboot makes of the sync, async transactional and observed order-placed listeners, from one real order. */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventListenerTraceCaptureIT {

    private static final String CONFIRMATION_SPAN = "OrderConfirmationListener#onOrderPlaced";

    private static final String STOCK_LEVEL_SPAN = "StockLevelListener#onOrderPlaced";

    /** The stock-level listener runs after the response, so its spans reach the store after the root span. */
    private static final Predicate<JsonNode> LISTENER_SPANS_CAPTURED =
            TraceApiClient.ROOT_SPAN_EXPORTED.and(trace -> SpanTree.names(trace)
                    .containsAll(List.of(CONFIRMATION_SPAN, STOCK_LEVEL_SPAN, AsyncTaskMarker.CONTEXTUAL_NAME)));

    @LocalServerPort
    private int port;

    private JsonNode trace;

    @BeforeAll
    void placeAnOrder() {
        TraceApiClient traces = new TraceApiClient(port);
        ResponseEntity<String> response = traces.restClient()
                .post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new NewOrder(1L, "WIDGET-3", 1))
                .retrieve()
                .toEntity(String.class);
        trace = traces.awaitTrace(TraceApiClient.traceIdOf(response.getHeaders()), LISTENER_SPANS_CAPTURED);
    }

    @Test
    void aSynchronousListenersQueryNestsUnderItsOwnSpan() {
        JsonNode listener = SpanTree.descendantNamed(trace, CONFIRMATION_SPAN);

        assertThat(SpanTree.statementsUnder(listener))
                .as("the listener's count query belongs to the listener, not to the handler beside it")
                .anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).contains("from order_line"));
        assertThat(listener.path("tags")
                        .path(EventListenerMarker.EVENT_TYPE_TAG_KEY)
                        .asString(""))
                .isEqualTo("OrderPlacedEvent");
    }

    @Test
    void anAsyncAfterCommitListenerIsItsOwnSpanInsideTheAsyncTask() {
        List<String> path = SpanTree.pathTo(trace, STOCK_LEVEL_SPAN);

        assertThat(path.get(path.size() - 2))
                .as("the hand-off runs first and the listener span opens on the executor thread; "
                        + "the other way round the span would time only the submission")
                .isEqualTo(AsyncTaskMarker.CONTEXTUAL_NAME);
        assertThat(SpanTree.statementsUnder(SpanTree.descendantNamed(trace, STOCK_LEVEL_SPAN)))
                .as("the stock read on the inventory DataSource belongs to the listener")
                .anySatisfy(sql -> assertThat(sql.toLowerCase(Locale.ROOT)).startsWith("select quantity from stock"));
    }

    @Test
    void anObservedListenerKeepsItsOwnSpanAndGetsNoSecond() {
        assertThat(Collections.frequency(SpanTree.names(trace), "order.placed")).isEqualTo(1);
        assertThat(SpanTree.names(trace)).doesNotContain("OrderPlacedListener#onOrderPlaced");
    }

    /** The trace store's listener runs for every log line this request writes. */
    @Test
    void peekabootsOwnListenersRaiseNoSpans() {
        assertThat(SpanTree.names(trace)).noneMatch(name -> name.startsWith("TraceStoreEventListener#"));
    }
}
