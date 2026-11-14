package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.PanelConfigLoader;
import org.peekaboot.backend.insights.config.PanelsFile;
import org.springframework.core.io.ClassPathResource;

class ProblemRuleTest {

    /** A rule that names a series no bundled panel ships is skipped on every read and never says so. */
    @Test
    void everyRuleReadsSeriesTheBundledPanelsShip() {
        PanelsFile bundled = PanelConfigLoader.load(new ClassPathResource("peekaboot-insights-defaults.yml"));
        Set<String> ids = bundled.panels().stream()
                .flatMap(panel -> panel.series().stream().map(panel::seriesId))
                .collect(Collectors.toSet());

        assertThat(ProblemRule.values())
                .allSatisfy(rule -> assertThat(ids).as(rule.name()).containsAll(rule.series()));
    }
}
