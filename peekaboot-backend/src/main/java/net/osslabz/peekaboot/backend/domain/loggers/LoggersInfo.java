package net.osslabz.peekaboot.backend.domain.loggers;

import java.util.List;

/**
 * Domain record for loggers information.
 *
 * Pre-groups loggers by package and pre-calculates counts
 * to move this logic from frontend to backend.
 */
public record LoggersInfo(
    List<LoggerGroup> packages,
    int totalCount,
    int configuredCount
) {}
