package org.peekaboot.backend.tracing.event;

import org.jspecify.annotations.Nullable;

/**
 * What a failed span recorded about its failure: its last {@code exception} event, or for a span
 * that failed without one, only its status description.
 *
 * @param type the exception's canonical class name; null without an exception event, and for an
 *     anonymous or local class, which the SDK cannot name
 * @param message the exception's message, else the span's status description; null where neither is set
 * @param stackTrace the trace the SDK rendered, capped at {@code CapturedStackTraces.MAX_LINES}; null
 *     without an exception event
 */
public record CapturedExceptionInfo(
        @Nullable String type,
        @Nullable String message,
        @Nullable String stackTrace) {}
