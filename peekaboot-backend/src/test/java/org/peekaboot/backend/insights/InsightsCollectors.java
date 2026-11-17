package org.peekaboot.backend.insights;

import java.util.Map;
import java.util.Optional;
import org.peekaboot.backend.insights.config.InsightsProperties;

/** Collaborators for an {@link InsightsCollector} whose events a test does not read, and reads of its rings. */
final class InsightsCollectors {

    private InsightsCollectors() {}

    // UncommentedEmptyMethodBody: the method's name is the documentation
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    static InsightsCollector.Listener noOpListener() {
        return new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {}

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {}
        };
    }

    /** Persistence off: the collector starts from empty rings. */
    static InsightsCollector.SnapshotSource noSnapshot() {
        return timeout -> Optional.empty();
    }

    /** Every entry of every ring, as a write with nothing persisted captures them. */
    static InsightsSnapshot everything(InsightsCollector collector) {
        // One zero end per level any collector can have; captureAfter reads only its own levels'.
        return collector.captureAfter(new long[InsightsProperties.MAX_LEVELS]);
    }

    /** Level 0's whole ring. */
    static TickLevelData ticks(InsightsCollector collector) {
        return (TickLevelData) collector.levelData(0, null);
    }

    /** The whole ring of {@code level}, which lies above 0. */
    static WindowLevelData windows(InsightsCollector collector, int level) {
        return (WindowLevelData) collector.levelData(level, null);
    }
}
