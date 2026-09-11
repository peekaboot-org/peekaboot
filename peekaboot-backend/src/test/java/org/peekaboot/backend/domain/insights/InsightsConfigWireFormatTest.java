package org.peekaboot.backend.domain.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.insights.config.Chart;
import org.peekaboot.backend.insights.config.Stat;
import org.peekaboot.backend.insights.config.TileFormat;
import org.peekaboot.backend.insights.config.Unit;

/**
 * The panel vocabulary enums replaced the YAML strings the frontend already reads off
 * {@code /api/insights/config}; the JSON must not have moved.
 */
class InsightsConfigWireFormatTest {

    @Test
    void chartUnitAndTileFormatSerialiseAsTheirYamlWords() {
        InsightsConfigResponse config = new InsightsConfigResponse(
                List.of(),
                List.of(new InsightsConfigResponse.Panel(
                        "net",
                        "Network",
                        Chart.BARS_LINE,
                        Unit.BYTES_PERSEC,
                        null,
                        List.of(new InsightsConfigResponse.Series("net.in", "In", Unit.PERSEC)))),
                List.of(new InsightsConfigResponse.Tile("started-at", "Started", TileFormat.DATETIME, false, 1.0)));

        String json = PeekabootJson.MAPPER.writeValueAsString(config);

        assertThat(json)
                .contains("\"chart\":\"bars-line\"")
                .contains("\"unit\":\"bytes-persec\"")
                .contains("\"unit\":\"persec\"")
                .contains("\"format\":\"datetime\"");
    }

    @Test
    void everyConstantKeepsItsYamlWord() {
        assertThat(Stream.of(Stat.values()).map(Stat::wireName)).containsExactly("value", "rate", "avg", "max");
        assertThat(Stream.of(Chart.values()).map(Chart::wireName)).containsExactly("line", "bars", "bars-line");
        assertThat(Stream.of(Unit.values()).map(Unit::wireName))
                .containsExactly("bytes", "percent", "millis", "count", "persec", "bytes-persec");
        assertThat(Stream.of(TileFormat.values()).map(TileFormat::wireName))
                .containsExactly("duration", "datetime", "bytes", "count");
    }
}
