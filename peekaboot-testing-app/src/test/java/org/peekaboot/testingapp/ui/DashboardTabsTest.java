package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardTabsTest extends PlaywrightTestBase {

    @Test
    void overviewShowsJavaAndSystemCards() {
        openDashboard();
        page.waitForSelector("#java-info .pk-kv");

        assertThat(page.textContent("#java-info")).contains("Version");
        assertThat(page.textContent("#os-info")).contains("Architecture");
    }

    @Test
    void healthBannerReflectsApplicationHealth() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.textContent("#health-status-text")).isEqualTo("UP");
    }

    @Test
    void memoryMeterIsRenderedWithTheSharedPrimitive() {
        openDashboard();
        page.waitForSelector("#memory-info .pk-meter__fill");

        assertThat(page.isVisible("#memory-info .pk-meter")).isTrue();
    }

    @Test
    void tabStripUsesAriaSelection() {
        openDashboard();

        String selected = (String) page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab");
        assertThat(selected).isEqualTo("dashboard");
    }

    @Test
    void switchingTabsUpdatesTheHashAndSelection() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#environment-tab.active");

        assertThat(page.url()).endsWith("#environment");
        assertThat(page.evaluate(
                "() => document.querySelector('.pk-tab[aria-selected=\"true\"]').dataset.tab"))
                .isEqualTo("environment");
    }

    @Test
    void deepLinkOpensTheRequestedTab() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#loggers");
        page.waitForSelector("#loggers-tab.active");

        assertThat(page.isVisible("#loggers-tab")).isTrue();
    }

    /**
     * The health banner used to be a click-handled <div> - not reachable by keyboard at
     * all. It is now a real <button> with aria-expanded/aria-controls; this proves Space
     * actually expands it, not just that a mouse click does (which every other test here
     * would still pass even if the element were a div with an onclick handler).
     */
    @Test
    void healthBannerExpandsWithTheKeyboard() {
        openDashboard();
        page.waitForFunction("() => document.querySelector('#health-status-text').textContent.trim() !== ''");

        assertThat(page.getAttribute("#health-banner", "aria-expanded")).isEqualTo("false");
        assertThat(page.isHidden("#health-components")).isTrue();

        page.focus("#health-banner");
        page.keyboard().press("Space");

        assertThat(page.getAttribute("#health-banner", "aria-expanded")).isEqualTo("true");
        assertThat(page.isVisible("#health-components")).isTrue();
    }

    /**
     * main.js fetches /api/features once at boot and unhides the traces/metrics tab
     * buttons directly from the result - the only place those two buttons are ever
     * unhidden, since neither has a tab module registered yet (Task 15). Tracing is
     * enabled in the test profile (see TraceOverlayTest/ToolbarTest, which depend on
     * real trace data), so this is a real assertion on a real feature flag, not a stub.
     */
    @Test
    void tracesTabIsUnhiddenWhenTracingIsAvailable() {
        openDashboard();
        page.waitForFunction(
                "() => !document.querySelector('.pk-tab[data-tab=\"traces\"]').classList.contains('hidden')");

        assertThat(page.isVisible(".pk-tab[data-tab='traces']")).isTrue();
    }

    @Test
    void environmentGroupsCollapseAndExpand() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#property-sources .pk-group__header");

        assertThat(page.isVisible("#property-sources .pk-group__list")).isFalse();

        page.click("#property-sources .pk-group__header");

        assertThat(page.isVisible("#property-sources .pk-group__list")).isTrue();
    }

    @Test
    void environmentFilterHighlightsMatches() {
        openDashboard();
        page.click(".pk-tab[data-tab='environment']");
        page.waitForSelector("#property-sources .pk-group");

        page.fill("#env-filter", "server.port");
        page.waitForSelector("#property-sources mark");

        assertThat(page.textContent("#property-sources mark")).contains("server.port");
    }

    @Test
    void expandedGroupSurvivesARefresh() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");
        page.click("#config-groups .pk-group__header");
        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();

        page.click("#refresh-btn");
        page.waitForTimeout(500);

        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();
    }

    @Test
    void loggersTabShowsLevelsAndRespectsConfiguredOnly() {
        openDashboard();
        page.click(".pk-tab[data-tab='loggers']");
        page.waitForSelector("#loggers-list .pk-group");

        int all = page.querySelectorAll("#loggers-list .pk-group").size();
        page.check("#loggers-configured-only");
        page.waitForTimeout(300);
        int configured = page.querySelectorAll("#loggers-list .pk-group").size();

        assertThat(configured).isLessThanOrEqualTo(all);
    }

    /**
     * Filters on "key", not "password": the test profile's H2 datasource has no bound
     * username/password (Spring's /configprops report omits unset properties entirely,
     * rather than masking them), so no property in the real payload ever contains
     * "password" to filter on. "key" does occur for real (e.g. management.observations'
     * "keyValues") and is masked by the same sensitive-key pattern, so it exercises the
     * same masking path against actual data instead of an unreachable filter term.
     */
    @Test
    void configTabMasksSensitiveValues() {
        openDashboard();
        page.click(".pk-tab[data-tab='config']");
        page.waitForSelector("#config-groups .pk-group__header");

        page.fill("#config-filter", "key");
        page.waitForTimeout(300);
        page.click("#config-groups .pk-group__header");

        assertThat(page.querySelectorAll("#config-groups .pk-kv__value--sensitive")).isNotEmpty();
    }
}
