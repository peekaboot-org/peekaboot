package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * With {@code peekaboot.enable-unmasking} unset - the default, and the state
 * {@link PlaywrightTestBase}'s own un-overridden Spring context already runs in, so no
 * property override is needed here - the unmask control must never appear anywhere in the
 * DOM. A control that cannot work must never be offered (see
 * {@code peekaboot-frontend/README.md}'s accessibility invariants). {@code
 * shared/unmask-control.js} leaves its slot empty rather than rendering a hidden button, so
 * this asserts genuine absence, not merely invisibility. See
 * {@link UnmaskingControlEnabledIT} for the complementary case.
 */
class UnmaskingControlDisabledIT extends PlaywrightTestBase {

    @Test
    void unmaskControlIsNotRenderedOnTheEnvironmentTab() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#property-sources .pk-group__header");

        assertThat(page.locator("#env-unmask-slot .pk-unmask-toggle").count()).isZero();
    }

    @Test
    void unmaskControlIsNotRenderedOnTheConfigTab() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");

        assertThat(page.locator("#config-unmask-slot .pk-unmask-toggle").count())
                .isZero();
    }
}
