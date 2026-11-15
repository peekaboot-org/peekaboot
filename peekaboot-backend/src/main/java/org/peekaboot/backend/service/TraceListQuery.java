package org.peekaboot.backend.service;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.tracing.store.TraceBucket;

/**
 * A trace listing request, every parameter read and checked.
 *
 * @param after the cursor of the last row the previous page served; null for the first page
 */
record TraceListQuery(
        int limit,
        Cursors.@Nullable Issued<RowPosition> after,
        TraceBucket bucket,
        TraceFilter filter,
        TraceView view) {

    static TraceListQuery of(TraceListParameters parameters) {
        String bucket = RequestParameters.text(parameters.bucket());
        String viewName = RequestParameters.text(parameters.view());
        TraceView view =
                viewName == null ? TraceView.SUMMARY : RequestParameters.enumValue("view", TraceView.class, viewName);
        return new TraceListQuery(
                PageLimit.of(parameters.limit(), view),
                Cursors.row(parameters.cursor()),
                bucket == null ? TraceBucket.ALL : RequestParameters.enumValue("bucket", TraceBucket.class, bucket),
                TraceFilter.of(parameters),
                view);
    }

    /** The row the previous page ended on; refuses a cursor another numbering handed out. */
    @Nullable
    RowPosition afterIn(long numbering) {
        return after == null ? null : after.under(numbering);
    }
}
