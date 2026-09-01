package org.peekaboot.backend.domain.trace;

/** Whether a span ended in error; serialised by name, the {@code "OK"}/{@code "ERROR"} the frontend reads. */
public enum SpanStatus {
    OK,
    ERROR
}
