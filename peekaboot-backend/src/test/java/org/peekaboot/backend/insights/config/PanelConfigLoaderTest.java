package org.peekaboot.backend.insights.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PanelConfigLoaderTest {

    private final ClassPathResource defaults = new ClassPathResource("insights/loader-defaults.yml");
    private final ClassPathResource user = new ClassPathResource("insights/loader-user.yml");
    private final ClassPathResource shipped = new ClassPathResource("insights/loader-shipped.yml");
    private final ClassPathResource patch = new ClassPathResource("insights/loader-patch.yml");

    private static PanelDef panelNamed(PanelsFile file, String id) {
        return file.panels().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void loadsDefaultsAlone() {
        PanelsFile file = PanelConfigLoader.load(defaults, null);
        assertThat(file.panels()).extracting(PanelDef::id).containsExactly("cpu", "heap");
        assertThat(file.tiles()).extracting(TileDef::id).containsExactly("uptime");
        PanelDef heap = file.panels().get(1);
        assertThat(heap.unit()).isEqualTo(Unit.BYTES);
        assertThat(heap.series().get(0).tags()).containsEntry("area", "heap");
    }

    /**
     * The YAML words carry a hyphen the enum constants cannot; the binder's lenient matching
     * is what bridges them, and nothing else in the loader does.
     */
    @Test
    void hyphenatedWordsBindToTheirConstants() {
        PanelsFile file = PanelConfigLoader.load(new ClassPathResource("insights/loader-hyphenated-words.yml"), null);

        PanelDef net = panelNamed(file, "net");
        assertThat(net.chart()).isEqualTo(Chart.BARS_LINE);
        assertThat(net.unit()).isEqualTo(Unit.BYTES_PERSEC);
        assertThat(net.series()).extracting(SeriesDef::unit).containsExactly(null, Unit.PERSEC);
    }

    @Test
    void defaultsStatToValue() {
        PanelsFile file = PanelConfigLoader.load(defaults, null);
        assertThat(file.panels().get(0).series().get(0).stat()).isEqualTo(Stat.VALUE);
    }

    @Test
    void userPanelReplacesSameIdWhollyAndReorders() {
        PanelsFile file = PanelConfigLoader.load(defaults, user);
        // heap got order 5 from the user file, so it now sorts first
        assertThat(file.panels()).extracting(PanelDef::id).containsExactly("heap", "cpu", "custom");
        assertThat(file.panels().get(0).title()).isEqualTo("My heap");
    }

    @Test
    void userCanAddDisabledPanels() {
        PanelsFile file = PanelConfigLoader.load(defaults, user);
        PanelDef custom = file.panels().get(2);
        assertThat(custom.enabled()).isFalse();
        assertThat(custom.series().get(0).stat()).isEqualTo(Stat.RATE);
    }

    /** The website's first by-id example: {@code - id: thread-states / enabled: true}, nothing else. */
    @Test
    void aTitlelessEntrySwitchesAShippedPanelOnByIdAlone() {
        PanelsFile file = PanelConfigLoader.load(shipped, patch);

        PanelDef threadStates = panelNamed(file, "thread-states");
        assertThat(threadStates.enabled()).isTrue();
        // everything the entry did not mention is the shipped panel's
        assertThat(threadStates.title()).isEqualTo("Thread states");
        assertThat(threadStates.chart()).isEqualTo(Chart.BARS);
        assertThat(threadStates.order()).isEqualTo(60);
        assertThat(threadStates.level()).isEqualTo(1);
        assertThat(threadStates.series()).extracting(SeriesDef::meter).containsExactly("jvm.threads.states");
    }

    /** The website's second by-id example: {@code - id: load / enabled: false}. */
    @Test
    void aTitlelessEntryHidesAShippedPanelWithoutRedefiningIt() {
        PanelsFile file = PanelConfigLoader.load(shipped, patch);

        PanelDef load = panelNamed(file, "load");
        assertThat(load.enabled()).isFalse();
        assertThat(load.title()).isEqualTo("System load");
        assertThat(load.series()).extracting(SeriesDef::meter).containsExactly("system.load.average.1m");
    }

    /** A title-less entry can only patch a panel that exists; under a new id there is nothing to patch. */
    @Test
    void aTitlelessEntryUnderAnUnknownIdIsRejected() {
        assertThatThrownBy(() ->
                        PanelConfigLoader.load(shipped, new ClassPathResource("insights/loader-patch-unknown-id.yml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing-ships-under-this-id")
                .hasMessageContaining("title");
    }

    @Test
    void missingUserResourceIsIgnored() {
        PanelsFile file = PanelConfigLoader.load(defaults, new ClassPathResource("insights/nope.yml"));
        assertThat(file.panels()).hasSize(2);
    }

    /** The collector keys its rings by id, so a second definition would silently replace the first. */
    @Test
    void rejectsDuplicatePanelIdsWithinOneFile() {
        assertThatThrownBy(() ->
                        PanelConfigLoader.load(new ClassPathResource("insights/loader-duplicate-panel.yml"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate panel id 'cpu'");
    }

    @Test
    void rejectsDuplicateSeriesIdsWithinOnePanel() {
        assertThatThrownBy(() ->
                        PanelConfigLoader.load(new ClassPathResource("insights/loader-duplicate-series.yml"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("panel 'heap': duplicate series id 'used'");
    }

    /** The binder rejects the word; the message still has to say where and what, since it is what the operator reads. */
    @Test
    void rejectsInvalidStatNamingThePropertyAndTheWord() {
        // loader-invalid.yml: single panel whose series has stat: bogus
        assertThatThrownBy(() -> PanelConfigLoader.load(new ClassPathResource("insights/loader-invalid.yml"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("panels[0].series[0].stat")
                .hasMessageContaining("bogus");
    }

    /** subtract-meter changes what value.currentValue subtracts from; rate/avg/max have no such term to subtract from. */
    @Test
    void rejectsSubtractMeterOnAStatOtherThanValue() {
        assertThatThrownBy(() ->
                        PanelConfigLoader.load(new ClassPathResource("insights/loader-subtract-meter-rate.yml"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("panel 'net'")
                .hasMessageContaining("series 'throughput'")
                .hasMessageContaining("stat 'rate'")
                .hasMessageContaining("subtract-meter");
    }

    /** The overview maps tile values by id in a flat map, so a second definition would silently replace the first. */
    @Test
    void rejectsDuplicateTileIdsWithinOneFile() {
        assertThatThrownBy(
                        () -> PanelConfigLoader.load(new ClassPathResource("insights/loader-duplicate-tile.yml"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate tile id 'uptime'");
    }
}
