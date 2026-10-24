package org.peekaboot.backend.insights;

import java.util.Map;
import java.util.Optional;

/** Collaborators for an {@link InsightsCollector} whose events a test does not read. */
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
}
