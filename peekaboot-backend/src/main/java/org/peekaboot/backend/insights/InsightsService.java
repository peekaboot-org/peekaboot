package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.peekaboot.backend.domain.insights.InsightsConfigResponse;
import org.peekaboot.backend.domain.insights.LevelDataResponse;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.PanelConfigLoader;
import org.peekaboot.backend.insights.config.PanelDef;
import org.peekaboot.backend.insights.config.PanelsFile;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;
import org.peekaboot.backend.storage.StorageDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Facade turning the {@link InsightsCollector} into API-ready DTOs: loads and
 * merges the panel/tile config, namespaces each panel's series, and exposes
 * the current config and per-level data as {@link InsightsConfigResponse} and
 * {@link LevelDataResponse}. Also a {@link SmartLifecycle}, delegating
 * start/stop to the collector.
 */
public final class InsightsService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsService.class);

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String DEFAULTS_LOCATION = "peekaboot-insights-defaults.yml";
    private static final String USER_LOCATION = "peekaboot-insights.yml";

    private final InsightsProperties properties;
    private final InsightsCollector collector;
    private final InsightsSnapshotStore store;
    private final List<PanelDef> panels;
    private final List<TileDef> tiles;
    private final int seriesCount;

    public InsightsService(
            MeterRegistry registry,
            InsightsProperties properties,
            ResourceLoader resourceLoader,
            InsightsCollector.Listener listener,
            StorageDirectory storage) {
        properties.validate();
        this.properties = properties;

        Resource defaults = resourceLoader.getResource(CLASSPATH_PREFIX + DEFAULTS_LOCATION);
        Resource userOverride = properties.getConfigLocation() != null
                ? resourceLoader.getResource(properties.getConfigLocation())
                : resourceLoader.getResource(CLASSPATH_PREFIX + USER_LOCATION);
        PanelsFile file = load(defaults, userOverride);

        this.panels = file.panels().stream()
                .filter(panel -> panel.enabled() == null || panel.enabled())
                .toList();
        this.tiles = file.tiles();

        List<SeriesDef> namespacedSeries = panels.stream()
                .flatMap(panel -> panel.series().stream().map(series -> namespaced(panel.id(), series)))
                .toList();
        this.seriesCount = namespacedSeries.size();

        this.store = InsightsSnapshotStore.create(storage, properties);
        this.collector = new InsightsCollector(
                properties.getLevels(),
                namespacedSeries,
                tiles,
                registry,
                listener,
                store != null ? store : InsightsCollector.SnapshotSource.NONE);
    }

    /**
     * The bundled defaults on their own first: they are ours, so a failure there is
     * our bug and has to surface. The user's override is merged on top separately,
     * because it is theirs - a typo in it costs them their panel customisation, not
     * the application Peekaboot is embedded in.
     */
    private static PanelsFile load(Resource defaults, Resource userOverride) {
        PanelsFile bundled = PanelConfigLoader.load(defaults, null);
        if (userOverride == null || !userOverride.exists()) {
            return bundled;
        }
        try {
            return PanelConfigLoader.load(defaults, userOverride);
        } catch (RuntimeException e) {
            log.error("Ignoring invalid insights panel config {}; using the bundled defaults", userOverride, e);
            return bundled;
        }
    }

    private static SeriesDef namespaced(String panelId, SeriesDef series) {
        return new SeriesDef(
                panelId + "." + series.id(),
                series.label(),
                series.meter(),
                series.tags(),
                series.stat(),
                series.subtractMeter(),
                series.unit());
    }

    @Override
    public void start() {
        if (store != null) {
            store.beginLoad();
        }
        collector.start();
        if (store != null) {
            store.start(collector::capture, collector::hasRestoredHistory);
        }
        log.info(
                "Peekaboot insights: {} series across {} panels, levels [{}], ring buffers ~{}{}",
                seriesCount,
                panels.size(),
                levelsDescription(),
                humanBytes(estimatedMemoryBytes()),
                store != null ? ", persisted across restarts" : "");
    }

    /**
     * The collector stops first, so the store's final capture sees rings nothing is
     * still writing to.
     */
    @Override
    public void stop() {
        collector.stop();
        if (store != null) {
            store.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return collector.isRunning();
    }

    public InsightsConfigResponse config() {
        List<InsightsConfigResponse.Level> levels = new ArrayList<>();
        List<InsightsProperties.Level> propertyLevels = properties.getLevels();
        for (int index = 0; index < propertyLevels.size(); index++) {
            InsightsProperties.Level level = propertyLevels.get(index);
            levels.add(
                    new InsightsConfigResponse.Level(index, level.getInterval().toMillis(), level.getSize()));
        }

        List<InsightsConfigResponse.Panel> panelResponses =
                panels.stream().map(this::toPanel).toList();

        Map<String, Double> tileValues = collector.tileValues();
        List<InsightsConfigResponse.Tile> tileResponses = tiles.stream()
                .map(tile -> new InsightsConfigResponse.Tile(
                        tile.id(), tile.label(), tile.format(), tile.live(), nanToNull(tileValues.get(tile.id()))))
                .toList();

        return new InsightsConfigResponse(levels, panelResponses, tileResponses);
    }

    private InsightsConfigResponse.Panel toPanel(PanelDef panel) {
        List<InsightsConfigResponse.Series> seriesResponses = panel.series().stream()
                .map(series -> new InsightsConfigResponse.Series(
                        panel.id() + "." + series.id(), series.label(), series.unit()))
                .toList();
        return new InsightsConfigResponse.Panel(
                panel.id(), panel.title(), panel.chart(), panel.unit(), panel.level(), seriesResponses);
    }

    public LevelDataResponse data(int level) {
        if (level < 0 || level >= properties.getLevels().size()) {
            throw new IllegalArgumentException("Unknown insights level: " + level);
        }
        return LevelDataResponse.from(collector.snapshot(level));
    }

    public int seriesCount() {
        return seriesCount;
    }

    public long estimatedMemoryBytes() {
        return InsightsCollector.estimateMemoryBytes(seriesCount, properties.getLevels());
    }

    InsightsCollector collector() {
        return collector;
    }

    private String levelsDescription() {
        return properties.getLevels().stream()
                .map(level -> InsightsCollector.formatInterval(level.getInterval()) + " x" + level.getSize())
                .collect(Collectors.joining(", "));
    }

    private static Double nanToNull(Double value) {
        return value == null || Double.isNaN(value) ? null : value;
    }

    /** Human-readable byte count, 1024-based, one decimal (e.g. {@code "5.8 MB"}). */
    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kilo = bytes / 1024.0;
        if (kilo < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kilo);
        }
        double mega = kilo / 1024.0;
        if (mega < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mega);
        }
        double giga = mega / 1024.0;
        return String.format(Locale.ROOT, "%.1f GB", giga);
    }
}
