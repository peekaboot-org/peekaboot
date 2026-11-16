package org.peekaboot.backend.mapper.trace;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.analysis.DbSpans;
import org.peekaboot.backend.domain.trace.SpanExceptionInfo;
import org.peekaboot.backend.domain.trace.SqlStatement;
import org.peekaboot.backend.domain.trace.TraceSummary;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TagMasker;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.event.SpanData;
import org.peekaboot.backend.tracing.store.TraceData;

/** Builds each kind of {@link SpanContent} a mapping can ask for, all from one masking policy. */
final class SpanContents {

    private final TagMasker tagMasker;
    private final SqlStatements sqlStatements;
    private final SpanExceptions spanExceptions;

    /** {@code sqlFormatter} is {@link SqlFormatter#NONE} where none is available. */
    SpanContents(MaskingEngine maskingEngine, SqlFormatter sqlFormatter, StackTraceRules stackTraceRules) {
        this.tagMasker = new TagMasker(maskingEngine);
        this.sqlStatements = new SqlStatements(maskingEngine, sqlFormatter);
        this.spanExceptions = new SpanExceptions(maskingEngine, stackTraceRules);
    }

    /** The content of a trace's {@code data}, for the one snapshot {@code traceData}. */
    ServedSpans served(TraceData traceData) {
        return new ServedSpans(new TraceQueries(traceData, sqlStatements));
    }

    /** The content a {@link TraceSummary} is read off. The tree it gives is never served itself. */
    SpanContent forSummary() {
        return new SummarySpans();
    }

    /** The content a trace's issues are judged on. Neither the tree it gives nor any part of it is served. */
    SpanContent forIssues() {
        return new IssueSpans();
    }

    /**
     * Every tag stays on its own span, masked - http.url etc. may carry a credential the
     * key name alone can't catch. A span's exception message and query text are masked the same
     * way: an exception message can echo back the failing request's URL. The statement
     * tags and the parameter tags are the exception: they are served once, masked, as the
     * span's {@code statement}, and shipping the raw tag beside it would say it twice. That
     * holds only on a query span, the one shape {@code statement} is populated for.
     */
    private Map<String, String> maskedTags(SpanData spanData) {
        Map<String, String> tags = spanData.tags();
        if (tags == null) {
            return Map.of();
        }
        boolean servedAsQuery = DbSpans.isQuery(spanData);
        Map<String, String> kept = new LinkedHashMap<>();
        tags.forEach((key, value) -> {
            if (!servedAsQuery || !DbSpans.isStatementTag(key)) {
                kept.put(key, value);
            }
        });
        return tagMasker.mask(kept);
    }

    /** Every part of a span the API serves, masked. */
    final class ServedSpans implements SpanContent {

        private final TraceQueries queries;

        private ServedSpans(TraceQueries queries) {
            this.queries = queries;
        }

        /** The statements and row counts of the trace's query spans, to share with {@link QueryExtractor}. */
        TraceQueries queries() {
            return queries;
        }

        @Override
        public Map<String, String> tags(SpanData span) {
            return maskedTags(span);
        }

        @Override
        public @Nullable SpanExceptionInfo exception(SpanData span) {
            return spanExceptions.of(span.exception());
        }

        @Override
        public @Nullable SqlStatement statement(SpanData span) {
            return queries.statement(span);
        }

        @Override
        public @Nullable Long rowCount(String spanId) {
            return queries.rowCount(spanId);
        }
    }

    /**
     * A summary serves a failed span's message and application frame and a repeated statement's text, so
     * those are masked. Tags only feed its verdicts, so they stay the stored map.
     */
    private final class SummarySpans implements SpanContent {

        @Override
        public Map<String, String> tags(SpanData span) {
            return span.tags();
        }

        @Override
        public @Nullable SpanExceptionInfo exception(SpanData span) {
            return spanExceptions.of(span.exception());
        }

        @Override
        public @Nullable SqlStatement statement(SpanData span) {
            return sqlStatements.textOnly(span);
        }

        @Override
        public @Nullable Long rowCount(String spanId) {
            return null;
        }
    }

    /**
     * An issue is judged on timing, tags and which statements repeat, and only its type leaves the
     * mapping. A statement is masked all the same: a summary compares statements by their masked
     * text, and the same ones have to repeat here.
     */
    private final class IssueSpans implements SpanContent {

        @Override
        public Map<String, String> tags(SpanData span) {
            return span.tags();
        }

        @Override
        public @Nullable SpanExceptionInfo exception(SpanData span) {
            return null;
        }

        @Override
        public @Nullable SqlStatement statement(SpanData span) {
            return sqlStatements.textOnly(span);
        }

        @Override
        public @Nullable Long rowCount(String spanId) {
            return null;
        }
    }
}
