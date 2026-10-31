package org.peekaboot.backend.tracing.store;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.event.SpanData;

/**
 * The spans a bundle's listing rows start from, read in one go by
 * {@link TraceDataBundle#listingRoots()}.
 *
 * @param root          the span the trace hangs from, the same one {@link TraceData#rootSpan()}
 *                      names; null while the bundle stores neither a span without a parent nor
 *                      one continuing a remote caller's trace
 * @param fragment      spans are stored but the trace is not complete: pieces of a request still
 *                      running, or of one whose root is never coming (an excluded request's)
 * @param asyncEntries  the spans that start an async subtree: a member whose resolved parent is
 *                      not one. Empty for the overwhelming majority of bundles
 */
public record ListingRoots(@Nullable SpanData root, boolean fragment, List<SpanData> asyncEntries) {}
