package org.peekaboot.backend.testsupport;

import java.util.Map;
import java.util.Optional;
import org.peekaboot.backend.insights.AggregateStats;
import org.peekaboot.backend.insights.InsightsCollector;

/** Collaborators for an {@link InsightsCollector} whose events a test does not read. */
public final class InsightsCollectors {

    private InsightsCollectors() {}

    // UncommentedEmptyMethodBody: the method's name is the documentation
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    public static InsightsCollector.Listener noOpListener() {
        return new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {}

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {}
        };
    }

    /** Persistence off: the collector starts from empty rings. */
    public static InsightsCollector.SnapshotSource noSnapshot() {
        return timeout -> Optional.empty();
    }
}
