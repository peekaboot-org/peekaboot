package org.peekaboot.backend.service;

import org.peekaboot.backend.domain.trace.TraceListParameters;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.tracing.store.TraceBucket;

/** A trace listing request, every parameter read and checked. */
record TraceListQuery(int limit, TraceBucket bucket, TraceFilter filter, TraceView view) {

    static final int DEFAULT_LIMIT = 100;

    /** Bounds one response's size. */
    static final int MAX_LIMIT = 10_000;

    static TraceListQuery of(TraceListParameters parameters) {
        Integer limit = RequestParameters.integer("limit", parameters.limit(), 1, MAX_LIMIT);
        String bucket = RequestParameters.text(parameters.bucket());
        String view = RequestParameters.text(parameters.view());
        return new TraceListQuery(
                limit != null ? limit : DEFAULT_LIMIT,
                bucket == null ? TraceBucket.ALL : RequestParameters.enumValue("bucket", TraceBucket.class, bucket),
                TraceFilter.of(parameters),
                view == null ? TraceView.SUMMARY : RequestParameters.enumValue("view", TraceView.class, view));
    }
}
