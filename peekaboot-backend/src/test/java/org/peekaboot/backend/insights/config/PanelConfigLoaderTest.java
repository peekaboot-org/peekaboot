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
        assertThat(heap.unit()).isEqualTo("bytes");
        assertThat(heap.series().get(0).tags()).containsEntry("area", "heap");
    }

    @Test
    void defaultsStatToValue() {
        PanelsFile file = PanelConfigLoader.load(defaults, null);
        assertThat(file.panels().get(0).series().get(0).stat()).isEqualTo("value");
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
        assertThat(custom.series().get(0).stat()).isEqualTo("rate");
    }

    /** The website's first by-id example: {@code - id: thread-states / enabled: true}, nothing else. */
    @Test
    void aTitlelessEntrySwitchesAShippedPanelOnByIdAlone() {
        PanelsFile file = PanelConfigLoader.load(shipped, patch);

        PanelDef threadStates = panelNamed(file, "thread-states");
        assertThat(threadStates.enabled()).isTrue();
        // everything the entry did not mention is the shipped panel's
        assertThat(threadStates.title()).isEqualTo("Thread states");
        assertThat(threadStates.chart()).isEqualTo("bars");
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

    @Test
    void rejectsInvalidStat() {
        // loader-invalid.yml: single panel whose series has stat: bogus
        assertThatThrownBy(() -> PanelConfigLoader.load(new ClassPathResource("insights/loader-invalid.yml"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bogus");
    }
}
