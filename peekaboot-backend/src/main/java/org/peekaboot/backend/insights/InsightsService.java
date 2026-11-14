package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.storage.StorageDirectory;
import org.peekaboot.backend.text.ByteFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.io.ResourceLoader;

/**
 * Runs the {@link InsightsCollector} over the {@link InsightsPanels} and its
 * {@link SnapshotStore}: a {@link SmartLifecycle} that starts and stops the two
 * in order, and the source of the current config as {@link InsightsConfigResponse}.
 * {@link InsightsDataReader} reads the rings.
 */
public final class InsightsService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InsightsService.class);

    private final InsightsProperties properties;
    private final InsightsPanels panels;
    private final InsightsCollector collector;
    private final SnapshotStore store;

    public InsightsService(
            MeterRegistry registry,
            InsightsProperties properties,
            ResourceLoader resourceLoader,
            InsightsCollector.Listener listener,
            StorageDirectory storage) {
        properties.validate();
        this.properties = properties;
        this.panels = InsightsPanels.load(resourceLoader, properties);
        this.store = InsightsSnapshotStore.create(storage, properties);
        this.collector = new InsightsCollector(
                properties.getLevels(),
                panels.series(),
                panels.shippedSeriesIds(),
                panels.tiles(),
                registry,
                listener,
                store);
    }

    @Override
    public void start() {
        store.beginLoad();
        collector.start();
        store.start(collector::capture, collector::hasRestoredHistory);
        log.info(
                "Peekaboot insights: {} series across {} panels, levels [{}], ring buffers ~{}{}",
                panels.series().size(),
                panels.panels().size(),
                levelsDescription(),
                ByteFormat.humanize(estimatedMemoryBytes()),
                store.persists() ? ", persisted across restarts" : "");
    }

    /**
     * The collector stops first, so the store's final capture sees rings nothing is
     * still writing to.
     */
    @Override
    public void stop() {
        collector.stop();
        store.stop();
    }

    @Override
    public boolean isRunning() {
        return collector.isRunning();
    }

    public InsightsConfigResponse config() {
        return InsightsConfigResponse.of(
                properties.getLevels(), panels.panels(), panels.tiles(), collector.tileValues());
    }

    private long estimatedMemoryBytes() {
        return SeriesRings.estimateMemoryBytes(panels.series().size(), properties.getLevels());
    }

    InsightsCollector collector() {
        return collector;
    }

    private String levelsDescription() {
        return properties.getLevels().stream()
                .map(level -> IntervalFormat.humanize(level.getInterval()) + " x" + level.getSize())
                .collect(Collectors.joining(", "));
    }
}
