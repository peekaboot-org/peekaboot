package org.peekaboot.backend.testsupport;

import java.util.Map;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.Stat;

/** Builds {@link SeriesDef} fixtures for the shapes the collector tests share. */
public final class SeriesDefs {

    private SeriesDefs() {}

    /** A plain value series over {@code meter}, labelled by its id, with no tags, subtraction or unit. */
    public static SeriesDef value(String id, String meter) {
        return new SeriesDef(id, id, meter, Map.of(), Stat.VALUE, null, null);
    }
}
