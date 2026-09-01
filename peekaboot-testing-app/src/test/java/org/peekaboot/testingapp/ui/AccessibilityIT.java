package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.ReducedMotion;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Accessibility regressions that no other test would notice: every assertion here is
 * about something invisible on screen, so a revert looks perfectly fine in a screenshot.
 */
class AccessibilityIT extends PlaywrightTestBase {

    /**
     * The header controls render a glyph inside the button. Text content outranks the
     * title attribute in the accessible-name algorithm, so without an explicit label the
     * name a screen reader announces is literally "↻", "❚❚" or "☾".
     */
    @Test
    void iconOnlyControlsHaveTextualAccessibleNames() {
        openDashboard();

        assertThat(page.getAttribute("#refresh-btn", "aria-label")).isEqualTo("Refresh now");
        assertThat(page.getAttribute("#pause-btn", "aria-label")).isEqualTo("Pause auto-refresh");
        assertThat(page.getAttribute("#theme-toggle", "aria-label")).contains("theme");
        assertThat(page.getAttribute("#error-close", "aria-label")).isEqualTo("Dismiss error");
        assertThat(page.getAttribute("#locale-select", "aria-label")).isEqualTo("Language");

        // and the glyphs themselves must not be read out alongside the label
        assertThat(page.getAttribute("#refresh-icon", "aria-hidden")).isEqualTo("true");
        assertThat(page.getAttribute("#pause-icon", "aria-hidden")).isEqualTo("true");
        assertThat(page.getAttribute("#theme-icon", "aria-hidden")).isEqualTo("true");
    }

    /** A toggle whose label says "Pause" while it resumes is worse than no label at all. */
    @Test
    void statefulControlLabelsFollowTheirState() {
        openDashboard();

        page.click("#pause-btn");
        assertThat(page.getAttribute("#pause-btn", "aria-label")).isEqualTo("Resume auto-refresh");
        page.click("#pause-btn");
        assertThat(page.getAttribute("#pause-btn", "aria-label")).isEqualTo("Pause auto-refresh");

        String before = page.getAttribute("#theme-toggle", "aria-label");
        page.click("#theme-toggle");
        assertThat(page.getAttribute("#theme-toggle", "aria-label")).isNotEqualTo(before);
    }

    /**
     * The Insights toolbar's global interval switch is a radio-like button group: the
     * group carries the name ("Aggregation level"), each segment's own name is its
     * interval, and aria-pressed - not just a background colour - says which one is on.
     * Every panel carries the same kind of group for its own (locally overridable)
     * level, labelled by the panel title, and the reset button beside it is icon-only,
     * so it needs a label of its own.
     */
    @Test
    void insightsLevelControlsAreLabelled() {
        openDashboard();
        page.click("#insights-tab-btn");
        page.waitForSelector("#insights-level .pk-insight-level");

        assertThat(page.getAttribute("#insights-level", "role")).isEqualTo("group");
        assertThat(page.getAttribute("#insights-level", "aria-label")).isEqualTo("Aggregation level");
        assertThat(page.locator("#insights-level .pk-insight-level[aria-pressed]")
                        .count())
                .isEqualTo(3);
        Object names = page.evaluate("() => [...document.querySelectorAll('#insights-level .pk-insight-level')]"
                + ".map(el => el.textContent.trim()).filter(Boolean)");
        assertThat((List<?>) names).hasSize(3);

        assertThat(page.getAttribute("#insights-panels .pk-insight-panel-levels", "role"))
                .isEqualTo("group");
        assertThat(page.getAttribute("#insights-panels .pk-insight-panel-levels", "aria-label"))
                .endsWith("aggregation level");
        assertThat(page.locator("#insights-panels .pk-insight-panel:first-child"
                                + " .pk-insight-panel-levels .pk-insight-level[aria-pressed='true']")
                        .count())
                .as("exactly one segment in a panel's own group is pressed")
                .isEqualTo(1);
        assertThat(page.getAttribute("#insights-panels .pk-insight-panel-reset", "aria-label"))
                .endsWith("to global interval");

        closeLiveStreams(); // the only test here that opens the Insights tab's SSE stream
    }

    /** The stat tiles' icons are decorative - the label beside them already names the value. */
    @Test
    void insightTileIconsAreHiddenFromAssistiveTech() {
        openDashboard();
        page.waitForSelector("#insights-tiles .pk-insight-tile");

        int icons = (int) (Integer) page.evaluate("() => document.querySelectorAll('.pk-insight-tile__icon').length");
        int hidden = (int) (Integer)
                page.evaluate("() => document.querySelectorAll('.pk-insight-tile__icon[aria-hidden=\"true\"]').length");
        assertThat(icons).isGreaterThan(0);
        assertThat(hidden).isEqualTo(icons);
    }

    /** Decorative emoji would otherwise be announced: "package Build", "seedling Spring". */
    @Test
    void decorativeCardIconsAreHiddenFromAssistiveTech() {
        openDashboard();

        int icons = (int) (Integer) page.evaluate("() => document.querySelectorAll('.pk-card__icon').length");
        int hidden = (int) (Integer)
                page.evaluate("() => document.querySelectorAll('.pk-card__icon[aria-hidden=\"true\"]').length");
        assertThat(icons).isGreaterThan(0);
        assertThat(hidden).isEqualTo(icons);
    }

    /**
     * Card titles are real headings, so heading navigation - a primary screen-reader
     * wayfinding mechanism - actually reaches the dashboard sections.
     */
    @Test
    void dashboardExposesAHeadingOutline() {
        openDashboard();

        assertThat((Integer) page.evaluate("() => document.querySelectorAll('h1').length"))
                .isEqualTo(1);
        assertThat((Integer) page.evaluate("() => document.querySelectorAll('h2').length"))
                .isGreaterThanOrEqualTo(6);
    }

    /** Without role="alert" the banner just un-hides and a failed refresh is silent. */
    @Test
    void errorBannerIsAnAlert() {
        openDashboard();

        assertThat(page.getAttribute("#error", "role")).isEqualTo("alert");
    }

    /**
     * The health dot pulses for as long as the dashboard is open. Under a reduce-motion
     * preference the animation must be cancelled, not merely shortened in wall-clock.
     */
    @Test
    void reducedMotionCancelsPerpetualAnimation() {
        page.emulateMedia(new Page.EmulateMediaOptions().setReducedMotion(ReducedMotion.REDUCE));
        openDashboard();

        // Read the number rather than the string: the computed value of a 0.01ms duration
        // serialises as "1e-05s" in Chromium, so pinning any literal spelling is brittle.
        double seconds = ((Number) page.evaluate(
                        "() => parseFloat(getComputedStyle(document.querySelector('.pk-spinner')).animationDuration)"))
                .doubleValue();

        assertThat(seconds).as("spinner animation-duration, in seconds").isLessThan(0.001);
        assertThat(cssVar(".pk-spinner", "animation-iteration-count")).isEqualTo("1");
    }

    /**
     * aria-modal="true" hides the page from assistive tech but does nothing for a sighted
     * keyboard user - Tab would walk straight out of the dialog. inert on the siblings is
     * what actually holds focus inside, and it must be released again on close or the
     * whole page stays unusable.
     */
    @Test
    void overlayMakesTheRestOfThePageInert() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/deadbeef");
        page.waitForFunction("() => !!document.getElementById('peekaboot-trace-overlay')"
                + "?.shadowRoot?.querySelector('.pk-overlay__error')");

        boolean siblingsInert = (boolean) page.evaluate("() => Array.from(document.body.children)"
                + ".filter(el => el.id !== 'peekaboot-trace-overlay').every(el => el.inert)");
        assertThat(siblingsInert).isTrue();

        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-overlay__error button').click()");
        page.waitForFunction("() => !document.getElementById('peekaboot-trace-overlay')");

        boolean anyStillInert =
                (boolean) page.evaluate("() => Array.from(document.body.children).some(el => el.inert)");
        assertThat(anyStillInert).isFalse();
    }

    /**
     * The 2026-08-22 audit gave .pk-copy a 24px min-height because a copy control is a real
     * click target, not a word in a sentence (components.css says so at the rule). The Logs
     * tab then stacks one inside a deliberately dense row, and the first attempt at fitting
     * it there cancelled exactly that floor with "min-height: auto; padding: 0" - a change
     * that looks like harmless tightening in a diff and like nothing at all in a screenshot.
     * Measures the rendered box rather than the declared property, so a future override
     * anywhere in the cascade fails here too.
     */
    @Test
    void logRowCopyControlsKeepTheMinimumHitTarget() {
        page.navigate(baseUrl + "/?error=true");
        page.waitForSelector("#peekaboot-toolbar-host");
        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-trace').textContent.trim() !== '-'");
        page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('.pk-toolbar').click()");
        page.waitForFunction(
                "() => !!document.getElementById('peekaboot-trace-overlay')?.shadowRoot"
                        + "?.querySelector('.pk-tab[data-tab=\"logs\"]')",
                null,
                new Page.WaitForFunctionOptions().setTimeout(15000));
        page.evaluate("() => document.getElementById('peekaboot-trace-overlay').shadowRoot"
                + ".querySelector('.pk-tab[data-tab=\"logs\"]').click()");
        page.waitForSelector(".pk-log__span-cell .pk-copy");

        BoundingBox box = page.locator(".pk-log__span-cell .pk-copy").first().boundingBox();

        assertThat(box.height)
                .as("a log row's copy control must not fall below the 24px hit-target floor "
                        + "components.css sets for every .pk-copy")
                .isGreaterThanOrEqualTo(24.0);
    }
}
