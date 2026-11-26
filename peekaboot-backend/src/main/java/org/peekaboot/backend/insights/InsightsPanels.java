package org.peekaboot.backend.insights;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.PanelConfigLoader;
import org.peekaboot.backend.insights.config.PanelDef;
import org.peekaboot.backend.insights.config.PanelsFile;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.TileDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/** The panel set the service runs with: bundled panels, the operator's override merged on top, disabled panels dropped. */
final class InsightsPanels {

    private static final Logger log = LoggerFactory.getLogger(InsightsPanels.class);

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String DEFAULTS_LOCATION = "peekaboot-insights-defaults.yml";
    private static final String USER_LOCATION = "peekaboot-insights.yml";

    private final List<PanelDef> panels;
    private final List<TileDef> tiles;
    private final List<SeriesDef> series;
    private final Set<String> shippedSeriesIds;

    private InsightsPanels(PanelsFile file, PanelsFile bundled) {
        this.panels = file.panels().stream()
                .filter(panel -> panel.enabled() == null || panel.enabled())
                .toList();
        this.tiles = file.tiles();
        this.series = panels.stream()
                .flatMap(panel -> panel.series().stream().map(series -> namespaced(panel, series)))
                .toList();
        this.shippedSeriesIds = shippedSeriesIds(panels, bundled);
    }

    static InsightsPanels load(ResourceLoader resourceLoader, InsightsProperties properties) {
        Resource defaults = resourceLoader.getResource(CLASSPATH_PREFIX + DEFAULTS_LOCATION);
        Resource userOverride = userOverride(resourceLoader, properties.getConfigLocation());
        int levelCount = properties.getLevels().size();
        PanelsFile bundled = withKnownLevels(PanelConfigLoader.load(defaults), levelCount);
        return new InsightsPanels(withUserOverride(bundled, userOverride, levelCount), bundled);
    }

    List<PanelDef> panels() {
        return panels;
    }

    List<TileDef> tiles() {
        return tiles;
    }

    /** Every panel's series under its namespaced id, carrying the unit it is charted in. */
    List<SeriesDef> series() {
        return series;
    }

    /**
     * The namespaced ids of the series that are exactly as shipped. A panel patched by id
     * alone keeps them; a series the operator wrote, or rewrote, is theirs.
     */
    Set<String> shippedSeriesIds() {
        return shippedSeriesIds;
    }

    /**
     * The operator's panel file. The one on the classpath root is optional. A location the
     * operator set and that names nothing is a mistake in the property, and serving the bundled
     * panels would hide it.
     */
    private static Resource userOverride(ResourceLoader resourceLoader, @Nullable String configLocation) {
        if (configLocation == null) {
            return resourceLoader.getResource(CLASSPATH_PREFIX + USER_LOCATION);
        }
        Resource configured = resourceLoader.getResource(configLocation);
        if (!configured.exists()) {
            throw new IllegalStateException("Insights panel config " + configured
                    + " does not exist. Check peekaboot.insights.config-location.");
        }
        return configured;
    }

    /**
     * The caller loads the bundled defaults on their own first: they are ours, so a failure
     * there is our bug and has to surface. The user's override is merged on top separately,
     * because it is theirs - a typo in it costs them their panel customisation, not
     * the application Peekaboot is embedded in.
     */
    private static PanelsFile withUserOverride(PanelsFile bundled, Resource userOverride, int levelCount) {
        try {
            return withKnownLevels(PanelConfigLoader.merge(bundled, userOverride), levelCount);
        } catch (RuntimeException e) {
            log.error(
                    "Insights panel config {} is invalid; discarding it entirely and serving the bundled panels"
                            + " instead of the operator's customisation",
                    userOverride,
                    e);
            return bundled;
        }
    }

    /** A panel's level picks one of the configured rings - a check the loader, which knows no levels, cannot make. */
    private static PanelsFile withKnownLevels(PanelsFile file, int levelCount) {
        for (PanelDef panel : file.panels()) {
            if (panel.level() != null && (panel.level() < 0 || panel.level() >= levelCount)) {
                throw new IllegalStateException("Invalid insights panel config: panel '" + panel.id() + "': level "
                        + panel.level() + " is not one of the " + levelCount + " configured levels");
            }
        }
        return file;
    }

    private static Set<String> shippedSeriesIds(List<PanelDef> panels, PanelsFile bundled) {
        Map<String, List<SeriesDef>> bundledSeries =
                bundled.panels().stream().collect(Collectors.toMap(PanelDef::id, PanelDef::series));
        Set<String> shipped = new HashSet<>();
        for (PanelDef panel : panels) {
            List<SeriesDef> candidates = bundledSeries.getOrDefault(panel.id(), List.of());
            panel.series().stream().filter(candidates::contains).forEach(series -> shipped.add(panel.seriesId(series)));
        }
        return shipped;
    }

    /** The sampler scales by the unit the series is charted in, so the series carries the panel's where it names none. */
    private static SeriesDef namespaced(PanelDef panel, SeriesDef series) {
        return new SeriesDef(
                panel.seriesId(series),
                series.label(),
                series.meter(),
                series.tags(),
                series.stat(),
                series.subtractMeter(),
                series.unit() != null ? series.unit() : panel.unit(),
                series.alternatives());
    }
}
