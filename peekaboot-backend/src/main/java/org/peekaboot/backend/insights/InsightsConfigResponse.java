package org.peekaboot.backend.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.insights.config.Chart;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.PanelDef;
import org.peekaboot.backend.insights.config.TileDef;
import org.peekaboot.backend.insights.config.TileFormat;
import org.peekaboot.backend.insights.config.Unit;

/**
 * Dashboard configuration: levels, enabled panels (with their series, already
 * namespaced as {@code <panelId>.<seriesId>}), and tiles with current values.
 */
public record InsightsConfigResponse(List<Level> levels, List<Panel> panels, List<Tile> tiles) {

    /** {@code tileValues} holds each tile's current reading by tile id; a tile without one answers null. */
    static InsightsConfigResponse of(
            List<InsightsProperties.Level> levels,
            List<PanelDef> panels,
            List<TileDef> tiles,
            Map<String, Double> tileValues) {
        List<Level> levelResponses = new ArrayList<>();
        for (int index = 0; index < levels.size(); index++) {
            InsightsProperties.Level level = levels.get(index);
            levelResponses.add(new Level(index, level.intervalMillis(), level.getSize()));
        }

        List<Panel> panelResponses =
                panels.stream().map(InsightsConfigResponse::toPanel).toList();

        List<Tile> tileResponses = tiles.stream()
                .map(tile -> new Tile(
                        tile.id(),
                        tile.label(),
                        tile.format(),
                        tile.live(),
                        PeekabootJson.finiteOrNull(tileValues.get(tile.id()))))
                .toList();

        return new InsightsConfigResponse(levelResponses, panelResponses, tileResponses);
    }

    private static Panel toPanel(PanelDef panel) {
        List<Series> seriesResponses = panel.series().stream()
                .map(series -> new Series(panel.seriesId(series), series.label(), series.unit()))
                .toList();
        return new Panel(panel.id(), panel.title(), panel.chart(), panel.unit(), panel.level(), seriesResponses);
    }

    public record Level(int index, long intervalMs, int size) {}

    public record Panel(String id, String title, Chart chart, Unit unit, Integer level, List<Series> series) {}

    /** {@code unit} null means inherit the panel's unit. */
    public record Series(String id, String label, Unit unit) {}

    /** {@code value} is null when the tile isn't (yet) resolvable. */
    public record Tile(String id, String label, TileFormat format, Boolean live, Double value) {}
}
