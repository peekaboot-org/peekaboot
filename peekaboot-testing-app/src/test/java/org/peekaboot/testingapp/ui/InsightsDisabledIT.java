package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** {@code peekaboot.insights.enabled=false}: a context whose /api/features genuinely reports a feature off. */
@TestPropertySource(properties = "peekaboot.insights.enabled=false")
class InsightsDisabledIT extends PlaywrightTestBase {

    /**
     * The negative half of the feature gating: the strip hides a tab whose feature is off.
     * The Traces tab is the positive control that only the insights feature is off.
     */
    @Test
    void aTabIsHiddenWhenItsFeatureIsOff() {
        openDashboard();

        assertThat(page.isVisible(Dashboard.tabButton("insights"))).isFalse();
        assertThat(page.isVisible(Dashboard.tabButton("traces")))
                .as("only the insights feature is off")
                .isTrue();
    }
}
