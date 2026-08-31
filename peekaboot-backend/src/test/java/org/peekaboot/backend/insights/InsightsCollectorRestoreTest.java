package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;

class InsightsCollectorRestoreTest {

    private static InsightsProperties.Level level(Duration interval, int size) {
        InsightsProperties.Level level = new InsightsProperties.Level();
        level.setInterval(interval);
        level.setSize(size);
        return level;
    }

    private static InsightsCollector collector(String... seriesIds) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.gauge("g", new AtomicLong(7));
        List<SeriesDef> series = List.of(seriesIds).stream()
                .map(id -> new SeriesDef(id, id, "g", Map.<String, String>of(), "value", null, null))
                .toList();
        return new InsightsCollector(
                List.of(level(Duration.ofSeconds(10), 90), level(Duration.ofMinutes(1), 60)),
                series,
                List.of(),
                registry,
                InsightsCollector.Listener.NO_OP);
    }

    @Test
    void capturedRingsComeBackWithTheirGapsAndTheirSampleCounts() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);
        source.tick(20_000);
        source.rollUp(1, 60_000);

        InsightsSnapshot snapshot = source.capture();
        InsightsCollector restored = collector("cpu.process");
        restored.restore(snapshot);

        LevelSnapshot level0 = restored.snapshot(0);
        assertThat(level0.count()).isEqualTo(2);
        assertThat(level0.endEpochMs()).isEqualTo(20_000);
        assertThat(level0.tickValues().get("cpu.process")).containsExactly(7.0, 7.0);
        assertThat(restored.snapshot(1).endEpochMs()).isEqualTo(60_000);
        assertThat(restored.snapshot(1).statValues().get("cpu.process").get("avg"))
                .containsExactly(7.0);
    }

    @Test
    void theSamplesColumnSurvivesSoTheNextRollUpCanWeightItsAverage() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);
        source.tick(20_000);
        source.rollUp(1, 60_000);

        double[][] level1 = source.capture().series().get("cpu.process").get(1);

        assertThat(InsightsSnapshot.STAT_COLUMNS.indexOf("samples")).isEqualTo(7);
        assertThat(level1[7]).containsExactly(2.0);
    }

    @Test
    void aSeriesTheConfigNoLongerKnowsIsDropped() {
        InsightsCollector source = collector("cpu.process", "gone.series");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process");
        restored.restore(source.capture());

        assertThat(restored.snapshot(0).tickValues()).containsOnlyKeys("cpu.process");
        assertThat(restored.snapshot(0).tickValues().get("cpu.process")).hasSize(1);
    }

    @Test
    void aSeriesTheFileNeverCarriedStartsEmpty() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process", "brand.new");
        restored.restore(source.capture());

        assertThat(restored.snapshot(0).tickValues().get("brand.new")).isEmpty();
    }

    @Test
    void theFirstTickAfterARestoreFillsTheGapTheOutageLeft() {
        InsightsCollector source = collector("cpu.process");
        source.tick(10_000);

        InsightsCollector restored = collector("cpu.process");
        restored.restore(source.capture());
        restored.tick(60_000); // four 10s boundaries missed

        assertThat(restored.snapshot(0).tickValues().get("cpu.process"))
                .containsExactly(7.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 7.0);
    }
}
