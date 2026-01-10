package net.osslabz.peekaboot.backend.domain.trace;

import java.util.List;

public record TraceInsightsResponse(
    List<TraceTree> traces,
    TraceSummary summary
) {}
