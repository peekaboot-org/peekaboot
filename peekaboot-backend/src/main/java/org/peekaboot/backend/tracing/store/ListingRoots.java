package org.peekaboot.backend.tracing.store;

import java.util.List;

/**
 * The spans a bundle's listing rows start from, read in one go by
 * {@link TraceDataBundle#listingRoots()}.
 *
 * @param root          the span the trace hangs from, the same one {@link TraceData#rootSpan()}
 *                      names; null for a bundle with no spans
 * @param asyncEntries  the spans that start an async subtree: a member whose resolved parent is
 *                      not one. Empty for the overwhelming majority of bundles
 */
public record ListingRoots(SpanData root, List<SpanData> asyncEntries) {}
