package org.peekaboot.backend.insights;

import java.util.List;
import org.peekaboot.backend.domain.trace.IssueSeverity;
import org.peekaboot.backend.insights.config.Unit;

/**
 * One threshold a window of the insights data reached, in the shape of a trace issue.
 *
 * @param series the series ids the rule read
 * @param value what the window measured, in {@code unit}
 * @param threshold the configured limit {@code value} reached
 * @param message one plain sentence that names the value
 */
public record InsightsProblem(
        ProblemRule rule,
        IssueSeverity severity,
        List<String> series,
        double value,
        double threshold,
        Unit unit,
        String message) {}
