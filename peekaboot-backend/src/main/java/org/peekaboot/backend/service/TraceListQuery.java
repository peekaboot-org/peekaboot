package org.peekaboot.backend.service;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.tracing.store.TraceBucket;

/**
 * A trace listing request, every parameter read and checked.
 *
 * @param after the position of the last row the previous page served; null for the first page
 */
record TraceListQuery(int limit, @Nullable RowPosition after, TraceBucket bucket, TraceFilter filter, TraceView view) {

    static TraceListQuery of(TraceListParameters parameters) {
        String bucket = RequestParameters.text(parameters.bucket());
        String view = RequestParameters.text(parameters.view());
        return new TraceListQuery(
                PageLimit.of(parameters.limit()),
                Cursors.row(parameters.cursor()),
                bucket == null ? TraceBucket.ALL : RequestParameters.enumValue("bucket", TraceBucket.class, bucket),
                TraceFilter.of(parameters),
                view == null ? TraceView.SUMMARY : RequestParameters.enumValue("view", TraceView.class, view));
    }
}
