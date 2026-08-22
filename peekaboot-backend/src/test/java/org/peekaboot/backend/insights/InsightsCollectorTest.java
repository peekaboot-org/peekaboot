package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsCollectorTest {

    private static InsightsProperties.Level level(Duration interval, int size) {
        InsightsProperties.Level level = new InsightsProperties.Level();
        level.setInterval(interval);
        level.setSize(size);
        return level;
    }

    private final List<InsightsProperties.Level> levels = List.of(
            level(Duration.ofSeconds(10), 6),
            level(Duration.ofMinutes(1), 10),
            level(Duration.ofMinutes(2), 5));

    private SimpleMeterRegistry registry;
    private AtomicLong gaugeValue;
    private List<String> events;
    private InsightsCollector collector;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        gaugeValue = registry.gauge("test.gauge", new AtomicLong(0));
        SeriesDef series = new SeriesDef("g", "G", "test.gauge", Map.of(), "value", null, null);
        TileDef staticTile = new TileDef("startup", "Startup", "app.start", Map.of(), "duration", false);
        TileDef liveTile = new TileDef("uptime", "Uptime", "app.uptime", Map.of(), "duration", true);
        events = new ArrayList<>();
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
                events.add("tick:" + values.get("g"));
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
                events.add("rollup:" + level + ":" + entries.get("g").avg());
            }
        };
        collector = new InsightsCollector(levels, List.of(series),
                List.of(staticTile, liveTile), registry, listener);
    }

    @Test
    void tickAppendsToLevelZeroAndNotifies() {
        gaugeValue.set(5);
        collector.tick(10_000);
        gaugeValue.set(7);
        collector.tick(20_000);
        LevelSnapshot snapshot = collector.snapshot(0);
        assertThat(snapshot.count()).isEqualTo(2);
        assertThat(snapshot.endEpochMs()).isEqualTo(20_000);
        assertThat(snapshot.tickValues().get("g")).containsExactly(5.0, 7.0);
        assertThat(events).containsExactly("tick:5.0", "tick:7.0");
    }

    @Test
    void rollUpAggregatesTicksIntoLevelOne() {
        for (int i = 1; i <= 6; i++) {
            gaugeValue.set(i * 10);
            collector.tick(i * 10_000L);
        }
        collector.rollUp(1, 60_000);
        LevelSnapshot snapshot = collector.snapshot(1);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.statValues().get("g").get("min")).containsExactly(10.0);
        assertThat(snapshot.statValues().get("g").get("max")).containsExactly(60.0);
        assertThat(snapshot.statValues().get("g").get("avg")).containsExactly(35.0);
        assertThat(events).contains("rollup:1:35.0");
    }

    @Test
    void levelTwoAggregatesLevelOneEntries() {
        // two full level-1 windows with different values
        for (int window = 0; window < 2; window++) {
            for (int i = 1; i <= 6; i++) {
                gaugeValue.set((window + 1) * 100);
                collector.tick((window * 6 + i) * 10_000L);
            }
            collector.rollUp(1, (window + 1) * 60_000L);
        }
        collector.rollUp(2, 120_000);
        LevelSnapshot snapshot = collector.snapshot(2);
        assertThat(snapshot.count()).isEqualTo(1);
        assertThat(snapshot.statValues().get("g").get("min")).containsExactly(100.0);
        assertThat(snapshot.statValues().get("g").get("max")).containsExactly(200.0);
        assertThat(snapshot.statValues().get("g").get("avg")).containsExactly(150.0);
    }

    @Test
    void staticTileFreezesAfterFirstResolution() {
        collector.tick(10_000); // app.start not registered yet -> NaN
        assertThat(collector.tileValues().get("startup")).isNaN();
        AtomicLong start = registry.gauge("app.start", new AtomicLong(42));
        collector.tick(20_000);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
        start.set(99); // static tile must NOT follow further changes
        collector.tick(30_000);
        assertThat(collector.tileValues().get("startup")).isEqualTo(42.0);
    }

    @Test
    void liveTileFollowsChanges() {
        AtomicLong uptime = registry.gauge("app.uptime", new AtomicLong(1));
        collector.tick(10_000);
        uptime.set(11);
        collector.tick(20_000);
        assertThat(collector.tileValues().get("uptime")).isEqualTo(11.0);
    }

    @Test
    void memoryEstimateMatchesFormula() {
        // 2 series x (90 + 1440*7 + 720*7) x 8 bytes
        List<InsightsProperties.Level> spec = List.of(
                level(Duration.ofSeconds(10), 90),
                level(Duration.ofMinutes(1), 1440),
                level(Duration.ofHours(1), 720));
        assertThat(InsightsCollector.estimateMemoryBytes(2, spec))
                .isEqualTo(2L * (90 + 1440 * 7 + 720 * 7) * 8);
    }
}
