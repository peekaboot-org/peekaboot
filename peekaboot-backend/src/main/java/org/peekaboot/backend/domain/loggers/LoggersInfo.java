package org.peekaboot.backend.domain.loggers;

import java.util.List;

/** Loggers grouped by package, with the counts already computed. */
public record LoggersInfo(List<LoggerGroup> packages, int totalCount, int configuredCount) {}
