package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** {@code peekaboot.lifecycle.enabled=false}: no run history to serve, so the strip hides the tab, as it does for Insights. */
@TestPropertySource(properties = "peekaboot.lifecycle.enabled=false")
class LifecycleDisabledIT extends PlaywrightTestBase {

    /** The Insights tab is the positive control that only the lifecycle feature is off. */
    @Test
    void theLifecycleTabIsHiddenWhenLifecycleIsOff() {
        openDashboard();

        assertThat(page.isVisible(Dashboard.tabButton("lifecycle"))).isFalse();
        assertThat(page.isVisible(Dashboard.tabButton("insights")))
                .as("only the lifecycle feature is off")
                .isTrue();
    }
}
