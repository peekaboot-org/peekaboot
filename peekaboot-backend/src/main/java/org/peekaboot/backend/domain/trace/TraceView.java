package org.peekaboot.backend.domain.trace;

/**
 * How much of each trace a listing carries, or of each series the insights data does. The summary alone keeps a
 * poll small.
 */
public enum TraceView {
    SUMMARY,
    FULL
}
