package org.peekaboot.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.BucketCounts;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceListResponse;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceResource;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.problem.ApiProblemException;
import org.peekaboot.backend.service.TraceInsightsService;
import org.peekaboot.backend.tracing.store.TraceBucket;

class TraceControllerTest {

    private TraceInsightsService traceInsightsService;

    private TraceController controller;

    @BeforeEach
    void setUp() {
        traceInsightsService = mock(TraceInsightsService.class);
        controller = new TraceController(traceInsightsService);
    }

    @Nested
    class ListTraces {

        @Test
        void shouldPassFiltersToService() {
            TraceListResponse expectedResponse = emptyListResponse();
            when(traceInsightsService.listTraces(100, TraceBucket.ALL, "SCHEDULED_JOB", "MyScheduler", "full"))
                    .thenReturn(expectedResponse);

            TraceListResponse result = controller.listTraces(100, null, "SCHEDULED_JOB", "MyScheduler", "full");

            assertThat(result).isSameAs(expectedResponse);
        }

        @Test
        void shouldPassParsedBucketToService() {
            controller.listTraces(100, "errors", null, null, null);

            verify(traceInsightsService).listTraces(100, TraceBucket.ERRORS, null, null, null);
        }

        @Test
        void shouldRefuseAnUnknownBucket() {
            assertThatThrownBy(() -> controller.listTraces(100, "not-a-bucket", null, null, null))
                    .isInstanceOfSatisfying(
                            ApiProblemException.class,
                            e -> assertThat(e.problem().parameter()).isEqualTo("bucket"));
        }

        @Test
        void shouldParseTheBucketRegardlessOfCase() {
            controller.listTraces(100, "SLOW", null, null, null);

            verify(traceInsightsService).listTraces(100, TraceBucket.SLOW, null, null, null);
        }

        @Test
        void shouldTreatAMissingOrBlankBucketAsAll() {
            controller.listTraces(100, null, null, null, null);
            controller.listTraces(100, "  ", null, null, null);

            verify(traceInsightsService, times(2)).listTraces(100, TraceBucket.ALL, null, null, null);
        }

        @Test
        void shouldClampNegativeLimit() {
            // a negative limit would throw from Stream.limit and produce a 500
            controller.listTraces(-5, null, null, null, null);

            verify(traceInsightsService).listTraces(eq(0), any(), any(), any(), any());
        }

        @Test
        void shouldClampExcessiveLimit() {
            // the cap bounds the response size
            controller.listTraces(Integer.MAX_VALUE, null, null, null, null);

            verify(traceInsightsService).listTraces(eq(10_000), any(), any(), any(), any());
        }

        /** The service reads the view, as it reads rootActionType. */
        @Test
        void passesTheViewThroughAsWritten() {
            controller.listTraces(100, null, null, null, "Full");

            verify(traceInsightsService).listTraces(100, TraceBucket.ALL, null, null, "Full");
        }

        private TraceListResponse emptyListResponse() {
            return new TraceListResponse(List.of(), BucketCounts.empty(), null);
        }
    }

    @Nested
    class GetTrace {

        @Test
        void shouldReturnTheTraceWhenFound() {
            TraceResource trace = createTrace("trace-456");
            when(traceInsightsService.getTrace("trace-456")).thenReturn(Optional.of(trace));

            assertThat(controller.getTrace("trace-456")).isSameAs(trace);
        }

        @Test
        void shouldRefuseAnUnknownTraceAsNotFound() {
            when(traceInsightsService.getTrace("trace-789")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.getTrace("trace-789"))
                    .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                        assertThat(e.problem().status()).isEqualTo(404);
                        assertThat(e.problem().parameter()).isNull();
                        assertThat(e.problem().detail()).contains("trace-789");
                    });
        }
    }

    private TraceResource createTrace(String traceId) {
        TraceSummary summary = new TraceSummary(
                traceId,
                Instant.EPOCH,
                100L,
                true,
                RootActionType.HTTP_REQUEST,
                "test-operation",
                null,
                TraceOutcome.OK,
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                new TraceSummary.Counts(
                        1, new TraceSummary.QueryCounts(0, 0L), new TraceSummary.LogCounts(0, 0, 0), 0, 0),
                null);
        return new TraceResource(summary, null);
    }
}
