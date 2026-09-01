package org.peekaboot.backend.insights.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PanelConfigLoaderTest {

    private final ClassPathResource defaults = new ClassPathResource("insights/loader-defaults.yml");
    private final ClassPathResource user = new ClassPathResource("insights/loader-user.yml");

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
