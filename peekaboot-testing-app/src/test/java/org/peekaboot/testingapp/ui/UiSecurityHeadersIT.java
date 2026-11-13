package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Response;
import org.junit.jupiter.api.Test;

/**
 * The policy UiSecurityHeadersInterceptor sends with the UI, enforced by a real browser on the
 * real dashboard. ApiHeadersIT pins the header's text; this proves that text allows everything
 * the dashboard and the trace overlay do, which no reading of the markup can.
 */
class UiSecurityHeadersIT extends PlaywrightTestBase {

    private static final String POLICY =
            "default-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'";

    /** A host no resolver answers, so the probe reaches nothing where the policy is not in force. */
    private static final String PROBE_HOST = "policy-probe.invalid";

    /**
     * Every tab of the dashboard, then one trace's overlay with every span's panels open and
     * each of its tabs: the persons page with {@code ?error=true} runs a query and logs an
     * ERROR with a stack trace, so no overlay tab is empty.
     */
    @Test
    void noDashboardTabViolatesThePolicy() {
        captureBrowserSignals();
        collectPolicyViolations();
        String traceId = openPageThatLogsAnError();

        Response navigation = page.waitForResponse(dashboardUrl(), this::openDashboard);

        assertThat(navigation.headers())
                .as("the policy reached the document under test")
                .containsEntry("content-security-policy", POLICY);
        for (String tabId : dashboard.visibleTabs()) {
            dashboard.openTab(tabId);
        }
        dashboard.openTracesTab();
        dashboard.awaitListedTrace(traceId);
        dashboard.openListedTrace(traceId);
        overlay.click(".pk-gantt-all-details");
        overlay.waitFor(".pk-gantt-span--open");
        for (String tab : overlay.tabs()) {
            overlay.openTab(tab);
        }
        awaitTheProbesViolation();

        assertThat(policyViolations())
                .as("the dashboard and the overlay violate nothing, so the probe's is the only one")
                .singleElement()
                .asString()
                .contains(PROBE_HOST);
    }

    /**
     * Asks for another origin, which the policy refuses. Violations are reported in the order
     * they happened, so once this one is recorded every earlier one is; and a collector that
     * recorded nothing at all would stop the test here instead of passing it.
     */
    private void awaitTheProbesViolation() {
        page.evaluate("host => fetch('http://' + host + '/').catch(() => {})", PROBE_HOST);
        page.waitForFunction("host => window.__policyViolations.some(v => v.includes(host))", PROBE_HOST);
    }
}
