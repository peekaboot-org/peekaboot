package org.peekaboot.backend.domain.trace;

import java.time.Instant;

public record SpanEvent(String name, Instant timestamp) {}
