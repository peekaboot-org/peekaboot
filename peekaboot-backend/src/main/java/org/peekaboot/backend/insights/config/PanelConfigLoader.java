package org.peekaboot.backend.insights.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * Loads the bundled panel file and merges an optional user override by id.
 * Uses Spring's YAML property-source machinery so no YAML library dependency
 * is needed anywhere in the starter.
 *
 * <p>An override entry with a title is a whole panel and replaces the bundled one of the
 * same id outright. An entry without a title is a patch: it switches a bundled panel on or
 * off, or moves it, by id alone - {@code enabled}, {@code order} and {@code level} are taken
 * from the entry, everything else stays the bundled panel's. A patch under an id nothing
 * ships is rejected, since there is nothing to patch.
 */
public final class PanelConfigLoader {

    private static final Set<String> STATS = Set.of("value", "rate", "avg", "max");
    private static final Set<String> CHARTS = Set.of("line", "bars", "bars-line");
    private static final Set<String> UNITS = Set.of("bytes", "percent", "millis", "count", "persec", "bytes-persec");
    private static final Set<String> TILE_FORMATS = Set.of("duration", "datetime", "bytes", "count");

    private PanelConfigLoader() {}

    public static PanelsFile load(Resource defaults, Resource userOverride) {
        PanelsFile file = validate(read(defaults));
        if (userOverride != null && userOverride.exists()) {
            file = merge(file, validate(read(userOverride)));
        }
        file.panels().forEach(PanelConfigLoader::requireTitle);
        return sorted(file);
    }

    private static PanelsFile read(Resource resource) {
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(resource.getFilename(), resource);
            if (sources.isEmpty()) {
                return new PanelsFile(List.of(), List.of());
            }
            ConfigurationPropertySource source =
                    ConfigurationPropertySources.from(sources.get(0)).iterator().next();
            Binder binder = new Binder(source);
            List<PanelDef> panels =
                    binder.bind("panels", Bindable.listOf(PanelDef.class)).orElse(List.of());
            List<TileDef> tiles =
                    binder.bind("tiles", Bindable.listOf(TileDef.class)).orElse(List.of());
            return new PanelsFile(panels, tiles);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load insights panel config " + resource, e);
        }
    }

    private static PanelsFile validate(PanelsFile file) {
        file.panels().forEach(PanelConfigLoader::validatePanel);
        file.tiles().forEach(PanelConfigLoader::validateTile);
        return withDefaults(file);
    }

    private static void validatePanel(PanelDef panel) {
        require(panel.id() != null, "panel without id");
        require(
                panel.chart() == null || CHARTS.contains(panel.chart()),
                "panel '" + panel.id() + "': unknown chart '" + panel.chart() + "'");
        require(
                panel.unit() == null || UNITS.contains(panel.unit()),
                "panel '" + panel.id() + "': unknown unit '" + panel.unit() + "'");
        for (SeriesDef series : panel.series()) {
            require(series.meter() != null, "panel '" + panel.id() + "': series without meter");
            require(
                    series.stat() == null || STATS.contains(series.stat()),
                    "panel '" + panel.id() + "': unknown stat '" + series.stat() + "'");
            require(
                    series.unit() == null || UNITS.contains(series.unit()),
                    "panel '" + panel.id() + "': unknown series unit '" + series.unit() + "'");
        }
    }

    private static void validateTile(TileDef tile) {
        require(tile.id() != null && tile.meter() != null, "tile without id/meter");
        require(
                tile.format() == null || TILE_FORMATS.contains(tile.format()),
                "tile '" + tile.id() + "': unknown format '" + tile.format() + "'");
    }

    /** Applies stat=value / chart=line / unit=count defaults so downstream code never sees nulls. */
    private static PanelsFile withDefaults(PanelsFile file) {
        List<PanelDef> panels = file.panels().stream()
                .map(p -> new PanelDef(
                        p.id(),
                        p.title(),
                        p.chart() == null ? "line" : p.chart(),
                        p.unit() == null ? "count" : p.unit(),
                        p.order(),
                        p.enabled(),
                        p.level(),
                        p.series().stream()
                                .map(s -> new SeriesDef(
                                        s.id() == null ? s.meter() : s.id(),
                                        s.label() == null ? s.meter() : s.label(),
                                        s.meter(),
                                        s.tags() == null ? Map.of() : s.tags(),
                                        s.stat() == null ? "value" : s.stat(),
                                        s.subtractMeter(),
                                        s.unit()))
                                .toList()))
                .toList();
        return new PanelsFile(panels, file.tiles());
    }

    /** A panel that survives the merge without a title was a patch that found nothing to patch. */
    private static void requireTitle(PanelDef panel) {
        require(panel.title() != null, "panel '" + panel.id() + "' has no title and patches no bundled panel");
    }

    private static PanelsFile merge(PanelsFile base, PanelsFile override) {
        Map<String, PanelDef> panels = new LinkedHashMap<>();
        base.panels().forEach(p -> panels.put(p.id(), p));
        override.panels().forEach(p -> panels.put(p.id(), isPatch(p, panels) ? patched(panels.get(p.id()), p) : p));
        Map<String, TileDef> tiles = new LinkedHashMap<>();
        base.tiles().forEach(t -> tiles.put(t.id(), t));
        override.tiles().forEach(t -> tiles.put(t.id(), t));
        return new PanelsFile(new ArrayList<>(panels.values()), new ArrayList<>(tiles.values()));
    }

    private static boolean isPatch(PanelDef entry, Map<String, PanelDef> base) {
        return entry.title() == null && base.containsKey(entry.id());
    }

    private static PanelDef patched(PanelDef base, PanelDef patch) {
        return new PanelDef(
                base.id(),
                base.title(),
                base.chart(),
                base.unit(),
                patch.order() != null ? patch.order() : base.order(),
                patch.enabled() != null ? patch.enabled() : base.enabled(),
                patch.level() != null ? patch.level() : base.level(),
                base.series());
    }

    private static PanelsFile sorted(PanelsFile file) {
        Comparator<PanelDef> byOrder = Comparator.comparingInt(
                        (PanelDef p) -> p.order() == null ? Integer.MAX_VALUE : p.order())
                .thenComparing(PanelDef::id);
        return new PanelsFile(file.panels().stream().sorted(byOrder).toList(), file.tiles());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid insights panel config: " + message);
        }
    }
}
