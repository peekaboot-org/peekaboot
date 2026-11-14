package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WindowStatisticTest {

    /** Every component distinct, so a statistic that reads its neighbour's cannot pass. */
    @Test
    void everyStatisticReadsItsOwnComponentUnderItsWireName() {
        AggregateStats stats = new AggregateStats(1, 2, 3, 4, 5, 6, 7, 8);

        Map<String, Double> read = new LinkedHashMap<>();
        for (WindowStatistic statistic : WindowStatistic.values()) {
            read.put(statistic.wireName(), statistic.of(stats));
        }

        assertThat(read)
                .containsExactly(
                        entry("min", 1.0),
                        entry("max", 2.0),
                        entry("avg", 3.0),
                        entry("median", 4.0),
                        entry("p90", 5.0),
                        entry("p95", 6.0),
                        entry("p99", 7.0));
    }
}
