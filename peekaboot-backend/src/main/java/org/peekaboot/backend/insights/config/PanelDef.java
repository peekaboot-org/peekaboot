package org.peekaboot.backend.insights.config;

import java.util.List;

/**
 * A single dashboard panel made up of one or more series.
 *
 * @param chart   {@code null} until the loader applies its {@link Chart#LINE} default
 * @param unit    {@code null} until the loader applies its {@link Unit#COUNT} default
 * @param enabled {@code null} means enabled
 */
public record PanelDef(
        String id,
        String title,
        Chart chart,
        Unit unit,
        Integer order,
        Boolean enabled,
        Integer level,
        List<SeriesDef> series) {

    public PanelDef {
        series = series == null ? List.of() : series;
    }

    /** The id {@code series} goes by outside this panel: in the collector, in the API and in a problem rule. */
    public String seriesId(SeriesDef series) {
        return id + "." + series.id();
    }
}
