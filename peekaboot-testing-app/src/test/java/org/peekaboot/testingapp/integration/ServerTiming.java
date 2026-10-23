package org.peekaboot.testingapp.integration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The trace id RequestCaptureFilter answers every captured request with, in its Server-Timing header. */
public final class ServerTiming {

    private static final Pattern TRACE_ID = Pattern.compile("trace;desc=\"00-([0-9a-f]+)-");

    private ServerTiming() {}

    /**
     * The trace id a Server-Timing header value names. A response without one was never
     * captured, which no later read could tell from a capture that lost everything, so that
     * fails here.
     */
    public static String traceId(String headerValue) {
        Matcher matcher = TRACE_ID.matcher(headerValue == null ? "" : headerValue);
        if (!matcher.find()) {
            throw new AssertionError(
                    "Server-Timing must carry the trace id, or the request was never captured: " + headerValue);
        }
        return matcher.group(1);
    }
}
