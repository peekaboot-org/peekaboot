package org.peekaboot.backend.domain.trace;

/**
 * The category of work a trace's root span represents. Serialized to the UI as the
 * constant name; the label and icon shown for each constant live with the rest of the
 * presentation layer, in the frontend's {@code shared/root-actions.js}.
 */
public enum RootActionType {
    HTTP_REQUEST,
    SCHEDULED_JOB,
    MESSAGE_CONSUMER,
    RPC_CALL,
    DATABASE,
    CONNECTION_POOL,
    INTERNAL,
    UNKNOWN
}
