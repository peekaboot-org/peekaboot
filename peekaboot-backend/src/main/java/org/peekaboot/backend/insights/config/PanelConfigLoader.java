package org.peekaboot.backend.insights.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.NestedExceptionUtils;
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
 *
 * <p>The vocabulary ({@link Stat}, {@link Chart}, {@link Unit}, {@link TileFormat}) is
 * checked by the binder, which reads the YAML words leniently into the enums; only the
 * rules that span fields are checked here.
 */
public final class PanelConfigLoader {

    private PanelConfigLoader() {}

    public static PanelsFile load(Resource defaults, Resource userOverride) {
        PanelsFile file = readValidated(defaults);
        if (userOverride != null && userOverride.exists()) {
            file = merge(file, readValidated(userOverride));
        }
        file.panels().forEach(PanelConfigLoader::requireTitle);
        return sorted(file);
    }

    /** Defaults go on before validation, so the rules below never meet a null stat, chart or unit. */
    private static PanelsFile readValidated(Resource resource) {
        PanelsFile file = withDefaults(read(resource));
        validate(file);
        return file;
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
        } catch (BindException e) {
            throw new IllegalStateException(
                    "Invalid insights panel config " + resource + ": " + e.getMessage() + ": "
                            + NestedExceptionUtils.getMostSpecificCause(e).getMessage(),
                    e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load insights panel config " + resource, e);
        }
    }

    private static void validate(PanelsFile file) {
        Set<String> panelIds = new HashSet<>();
        for (PanelDef panel : file.panels()) {
            validatePanel(panel);
            require(panelIds.add(panel.id()), "duplicate panel id '" + panel.id() + "'");
        }
        Set<String> tileIds = new HashSet<>();
        for (TileDef tile : file.tiles()) {
            validateTile(tile);
            require(tileIds.add(tile.id()), "duplicate tile id '" + tile.id() + "'");
        }
    }

    private static void validatePanel(PanelDef panel) {
        require(panel.id() != null, "panel without id");
        Set<String> seriesIds = new HashSet<>();
        for (SeriesDef series : panel.series()) {
            require(series.meter() != null, "panel '" + panel.id() + "': series without meter");
            require(
                    series.subtractMeter() == null || series.stat() == Stat.VALUE,
                    "panel '" + panel.id() + "': series '" + series.id() + "': subtract-meter is not supported"
                            + " for stat '" + series.stat().wireName() + "'");
            require(
                    seriesIds.add(series.id()),
                    "panel '" + panel.id() + "': duplicate series id '" + series.id() + "'");
        }
    }

    /** A series without an id is addressed by its meter. */
    private static String idOf(SeriesDef series) {
        return series.id() == null ? series.meter() : series.id();
    }

    private static void validateTile(TileDef tile) {
        require(tile.id() != null && tile.meter() != null, "tile without id/meter");
    }

    /** Applies the id, label, stat, chart and unit defaults, so nothing after this ever sees a null in their place. */
    private static PanelsFile withDefaults(PanelsFile file) {
        List<PanelDef> panels = file.panels().stream()
                .map(p -> new PanelDef(
                        p.id(),
                        p.title(),
                        p.chart() == null ? Chart.LINE : p.chart(),
                        p.unit() == null ? Unit.COUNT : p.unit(),
                        p.order(),
                        p.enabled(),
                        p.level(),
                        p.series().stream()
                                .map(s -> new SeriesDef(
                                        idOf(s),
                                        s.label() == null ? s.meter() : s.label(),
                                        s.meter(),
                                        s.tags(),
                                        s.stat() == null ? Stat.VALUE : s.stat(),
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
