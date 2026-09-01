package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code peekaboot.enable-unmasking=true} - the server-side half of the two-independent-
 * opt-ins design (see {@code UnmaskingEnabledIT} for the HTTP-level proof).
 * This exercises the dashboard control that drives the request-side half, the {@code
 * unmask=true} query parameter, end to end through a real browser.
 *
 * <p>A separate Spring context from the rest of the {@code ui} suite is required for the
 * same reason {@code UnmaskingEnabledIT} needs its own:
 * {@code peekaboot.enable-unmasking} is read once at context startup via
 * {@code PeekabootProperties}, so the enabled/disabled states can't share a context. The
 * {@code @SpringBootTest} redeclared here (matching {@link PlaywrightTestBase}'s own
 * annotation except for {@code properties}) takes precedence over the inherited one -
 * Spring's test framework resolves the closest occurrence in the class hierarchy.
 *
 * <p>{@code application-test.yml} binds {@code spring.datasource.password} as a fixture
 * value purely so this test has a real, secret-looking property to reveal (see
 * {@code DashboardTabsIT.configTabMasksSensitiveValues} for the masked-by-default half
 * of the same fixture).
 */
@SpringBootTest(
        classes = TestingApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "peekaboot.enable-unmasking=true")
@ActiveProfiles("test")
class UnmaskingControlEnabledIT extends PlaywrightTestBase {

    @Test
    void togglingTheControlRevealsAMaskedConfigValue() {
        openDashboard();
        openConfigPasswordRow();

        assertThat(configPasswordValue()).isEqualTo("******");
        assertThat(page.getAttribute("#config-unmask-slot .pk-unmask-toggle", "aria-pressed"))
                .isEqualTo("false");

        page.click("#config-unmask-slot .pk-unmask-toggle");
        waitForConfigPasswordValue("test-fixture-password");

        assertThat(page.getAttribute("#config-unmask-slot .pk-unmask-toggle", "aria-pressed"))
                .isEqualTo("true");
    }

    /**
     * Proves the control is per-view state, not persisted: main.js keeps
     * {@code unmaskRequested} as a plain module-scoped variable, never written to
     * localStorage (unlike the theme/locale/timezone preferences it sits beside), so a
     * fresh page load - simulated here by navigating to the dashboard again, same as
     * {@link PlaywrightTestBase#openDashboard()} does for the first load - always starts
     * masked regardless of what was revealed before.
     */
    @Test
    void reloadingThePageReturnsToMasked() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-unmask-slot .pk-unmask-toggle");
        page.click("#config-unmask-slot .pk-unmask-toggle");
        page.waitForFunction(
                "() => document.querySelector('#config-unmask-slot .pk-unmask-toggle').getAttribute('aria-pressed') === 'true'");

        openDashboard();
        openConfigPasswordRow();

        assertThat(configPasswordValue()).isEqualTo("******");
        assertThat(page.getAttribute("#config-unmask-slot .pk-unmask-toggle", "aria-pressed"))
                .isEqualTo("false");
    }

    /**
     * Environment and Config both render off the one combined {@code /insights} payload
     * main.js fetches, so the reveal state is deliberately one shared boolean rather than
     * two independently toggleable copies (see {@code shared/unmask-control.js}'s doc
     * comment) - toggling on the Config tab must also reveal the Environment tab's copy of
     * the same underlying property, with no second click needed there.
     */
    @Test
    void togglingOnTheConfigTabAlsoRevealsTheEnvironmentTab() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-unmask-slot .pk-unmask-toggle");
        page.click("#config-unmask-slot .pk-unmask-toggle");
        page.waitForFunction(
                "() => document.querySelector('#config-unmask-slot .pk-unmask-toggle').getAttribute('aria-pressed') === 'true'");

        page.click(".pk-tab[data-tab='environment']");
        page.fill("#env-filter", "spring.datasource.password");
        // Matches DashboardTabsIT.environmentFilterHighlightsMatches: waiting for a
        // generic group header risks resolving against the pre-filter list still on
        // screen, since filtering never auto-expands a group.
        page.waitForSelector(
                "#property-sources mark", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        page.click("#property-sources .pk-group__header");

        assertThat(environmentPasswordValue()).isEqualTo("test-fixture-password");
        assertThat(page.getAttribute("#env-unmask-slot .pk-unmask-toggle", "aria-pressed"))
                .isEqualTo("true");
    }

    private void openConfigPasswordRow() {
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");
        page.fill("#config-filter", "password");
        // Rows render into the DOM regardless of the group's expand/collapse state - see
        // DashboardTabsIT.configTabMasksSensitiveValues.
        page.waitForSelector(
                "#config-groups .pk-kv__key",
                new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        page.click("#config-groups .pk-group__header");
    }

    private String configPasswordValue() {
        return (String) page.evaluate("""
            () => {
                const row = Array.from(document.querySelectorAll('#config-groups .pk-kv'))
                    .find(r => r.querySelector('.pk-kv__key').textContent === 'password');
                return row ? row.querySelector('.pk-kv__value').textContent : null;
            }
            """);
    }

    private void waitForConfigPasswordValue(String expected) {
        page.waitForFunction("""
            (expected) => {
                const row = Array.from(document.querySelectorAll('#config-groups .pk-kv'))
                    .find(r => r.querySelector('.pk-kv__key').textContent === 'password');
                return row && row.querySelector('.pk-kv__value').textContent === expected;
            }
            """, expected);
    }

    private String environmentPasswordValue() {
        return (String) page.evaluate("""
            () => {
                const row = Array.from(document.querySelectorAll('#property-sources .pk-kv'))
                    .find(r => r.querySelector('.pk-kv__key').textContent === 'spring.datasource.password');
                return row ? row.querySelector('.pk-kv__value').textContent : null;
            }
            """);
    }
}
