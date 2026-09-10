package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.deque.html.axecore.playwright.AxeBuilder;
import com.deque.html.axecore.results.AxeResults;
import com.deque.html.axecore.results.Rule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * axe-core over the three surfaces, at the WCAG levels the project holds itself to.
 * {@code AccessibilityIT} pins the regressions this project has actually had, one assertion
 * each; this is the other direction - a control that arrives unlabelled, a contrast pair
 * nobody measured, a landmark that stopped being one. Neither replaces the other: the sweep
 * says nothing about the reasoning behind a targeted test, and a targeted test cannot notice a
 * rule nobody thought of.
 */
class AccessibilitySweepIT extends PlaywrightTestBase {

    /** WCAG 2.1 A and AA, the level the project's contrast work targets; axe's own best practices are advisory. */
    private static final List<String> WCAG_AA = List.of("wcag2a", "wcag2aa", "wcag21a", "wcag21aa");

    @Test
    void theDashboardHasNoAccessibilityViolations() {
        openDashboard();

        assertNoViolations(sweep());
    }

    /**
     * Opened over the dashboard rather than from the toolbar, so the document around it is
     * Peekaboot's own: the sample app's pages are the consumer's markup, and a sweep of them
     * would report the consumer's own oversights as Peekaboot's.
     */
    @Test
    void theTraceOverlayHasNoAccessibilityViolations() {
        String traceId = openPageThatLogsAnError();

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces/" + traceId);
        overlay.waitFor(".pk-tab");

        assertNoViolations(sweep());
    }

    /**
     * The bar lives in a shadow root on a page Peekaboot does not own, so the sweep is scoped
     * to that root: what the sample app's own markup does is not Peekaboot's to answer for.
     */
    @Test
    void theDevToolbarHasNoAccessibilityViolations() {
        openPersonsPage();
        toolbar.traceId();

        assertNoViolations(new AxeBuilder(page)
                .include("#peekaboot-toolbar-host")
                .withTags(WCAG_AA)
                .analyze());
    }

    private AxeResults sweep() {
        return new AxeBuilder(page).withTags(WCAG_AA).analyze();
    }

    private static void assertNoViolations(AxeResults results) {
        assertThat(results.getViolations())
                .as("axe-core violations: %s", describe(results.getViolations()))
                .isEmpty();
    }

    /** The rule id and the nodes it failed on; the raw result object prints as an object reference. */
    private static String describe(List<Rule> violations) {
        return violations.stream()
                .map(rule -> rule.getId() + " on " + rule.getNodes().size() + " node(s): " + rule.getHelp())
                .toList()
                .toString();
    }
}
