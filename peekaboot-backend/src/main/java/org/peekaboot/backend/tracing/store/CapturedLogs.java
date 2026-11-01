package org.peekaboot.backend.tracing.store;

import java.util.List;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

/**
 * One read of a trace's logs.
 *
 * @param logs the logs the cap kept, oldest first
 * @param droppedLogs the logs the cap trimmed before this read
 */
public record CapturedLogs(List<LogCapturedEvent> logs, int droppedLogs) {}
