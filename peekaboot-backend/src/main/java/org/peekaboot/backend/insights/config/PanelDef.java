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
}
