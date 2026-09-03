package org.peekaboot.backend.insights.config;

import java.util.List;

/**
 * A single dashboard panel made up of one or more series.
 *
 * @param chart   one of {@code line|bars|bars-line}
 * @param enabled {@code null} means enabled
 */
public record PanelDef(
        String id,
        String title,
        String chart,
        String unit,
        Integer order,
        Boolean enabled,
        Integer level,
        List<SeriesDef> series) {

    public PanelDef {
        series = series == null ? List.of() : series;
    }
}
