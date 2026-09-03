package org.peekaboot.backend.insights.config;

import java.util.List;

/** The full set of dashboard panels and tiles, as bound from YAML. */
public record PanelsFile(List<PanelDef> panels, List<TileDef> tiles) {

    public PanelsFile {
        panels = panels == null ? List.of() : panels;
        tiles = tiles == null ? List.of() : tiles;
    }
}
