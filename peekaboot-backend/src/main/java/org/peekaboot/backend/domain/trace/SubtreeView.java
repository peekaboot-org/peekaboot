package org.peekaboot.backend.domain.trace;

/**
 * Marks a {@link TraceTree} as a view of part of a trace rather than the whole of it.
 *
 * @param rootSpanId             the span this view is rooted at
 * @param enclosedByStoredTrace  whether the trace still holds this span's parent, and so
 *                               whether there is an enclosing trace to link back to. False for
 *                               an orphan, where the parent was evicted before this span arrived
 */
public record SubtreeView(String rootSpanId, boolean enclosedByStoredTrace) {}
