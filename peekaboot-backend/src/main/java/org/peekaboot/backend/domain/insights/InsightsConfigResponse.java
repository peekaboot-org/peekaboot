package org.peekaboot.backend.domain.insights;

import java.util.List;
import org.peekaboot.backend.insights.config.Chart;
import org.peekaboot.backend.insights.config.TileFormat;
import org.peekaboot.backend.insights.config.Unit;

/**
 * Dashboard configuration: levels, enabled panels (with their series, already
 * namespaced as {@code <panelId>.<seriesId>}), and tiles with current values.
 */
public record InsightsConfigResponse(List<Level> levels, List<Panel> panels, List<Tile> tiles) {

    public record Level(int index, long intervalMs, int size) {}

    public record Panel(String id, String title, Chart chart, Unit unit, Integer level, List<Series> series) {}

    /** {@code unit} null means inherit the panel's unit. */
    public record Series(String id, String label, Unit unit) {}

    /** {@code value} is null when the tile isn't (yet) resolvable. */
    public record Tile(String id, String label, TileFormat format, Boolean live, Double value) {}
}
