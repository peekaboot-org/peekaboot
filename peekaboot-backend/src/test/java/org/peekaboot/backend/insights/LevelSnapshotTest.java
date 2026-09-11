package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.insights.LevelDataResponse;

class LevelSnapshotTest {

    /** JSON has no NaN, so every gap inside an aggregated column has to leave as null. */
    @Test
    void anAggregatedLevelMapsNaNToNullInsideEachStatColumn() {
        LevelSnapshot snapshot = new LevelSnapshot(
                1,
                60_000,
                120_000,
                2,
                Map.of(),
                Map.of("g", Map.of("avg", new double[] {5.0, Double.NaN}, "max", new double[] {Double.NaN, 9.0})));

        LevelDataResponse response = snapshot.toResponse();

        LevelDataResponse.SeriesData series = response.series().get("g");
        assertThat(series.values())
                .as("an aggregated level carries stats, not raw values")
                .isNull();
        assertThat(series.stats().get("avg")).containsExactly(5.0, null);
        assertThat(series.stats().get("max")).containsExactly(null, 9.0);
        assertThat(response.level()).isEqualTo(1);
        assertThat(response.count()).isEqualTo(2);
    }
}
