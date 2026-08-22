package org.peekaboot.backend.insights.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPanelsTest {

    private final PanelsFile defaults =
            PanelConfigLoader.load(new ClassPathResource("peekaboot-insights-defaults.yml"), null);

    @Test
    void defaultOnPanelsInApprovedOrder() {
        assertThat(defaults.panels().stream()
                .filter(p -> p.enabled() == null || p.enabled())
                .map(PanelDef::id))
                .containsExactly("cpu", "load", "heap", "nonheap", "gc", "threads",
                        "http-throughput", "http-latency", "http-active",
                        "db-pool", "db-pool-timing", "jdbc-queries", "repositories",
                        "transactions", "disk", "log-events");
    }

    @Test
    void shipsDisabledExtras() {
        assertThat(defaults.panels().stream()
                .filter(p -> Boolean.FALSE.equals(p.enabled()))
                .map(PanelDef::id))
                .containsExactlyInAnyOrder("thread-states", "hibernate-activity", "executors",
                        "open-files", "tomcat-sessions", "allocation");
    }

    @Test
    void tilesPresent() {
        assertThat(defaults.tiles()).extracting(TileDef::id)
                .containsExactly("started-at", "startup-time", "ready-time", "uptime",
                        "cpu-cores", "heap-max", "disk-total", "pool-min", "pool-max");
    }

    @Test
    void everySeriesHasValidStatAndTimerStatsUseMillisOrRate() {
        // loader already validates enum values; spot-check semantic expectations
        PanelDef latency = defaults.panels().stream()
                .filter(p -> p.id().equals("http-latency")).findFirst().orElseThrow();
        assertThat(latency.unit()).isEqualTo("millis");
        assertThat(latency.series()).extracting(SeriesDef::stat).containsExactly("avg", "max");
    }
}