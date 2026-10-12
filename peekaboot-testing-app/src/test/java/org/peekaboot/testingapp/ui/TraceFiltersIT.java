package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.AriaRole;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TraceFiltersIT extends PlaywrightTestBase {

    /** The observed datasource - a connection acquired on it outside any traced work starts a pool trace. */
    @Autowired
    private DataSource dataSource;

    private static final Pattern ROOT_ACTION_TYPE_PARAM = Pattern.compile("[?&]rootActionType=([^&]*)");

    /** A root action type's own chip in the traces filter row, as opposed to the exclusion box beside them. */
    private static final String TRACE_TYPE_CHIP = "#traces-filter input[name='type']";

    private static final String CONNECTION_POOL_EXCLUSION = "#traces-filter-exclude-pool";

    private static final String CONNECTION_POOL_ROW_ICON =
            "#traces-list .pk-trace-item__icon[aria-label='Connection Pool']";

    /**
     * A deep link into the traces tab must restore the bucket and type filter controls
     * from the URL, not just land on the traces tab - a filtered traces URL is meant to
     * be shareable/bookmarkable.
     */
    @Test
    void deepLinkRestoresTheTracesBucketAndTypeFilter() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=errors&type=SCHEDULED_JOB");
        page.waitForSelector("#traces-bucket .pk-btn[data-bucket='errors'][aria-pressed='true']");

        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='all']", "aria-pressed"))
                .isEqualTo("false");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='errors']", "aria-pressed"))
                .isEqualTo("true");

        Object checkedTypesRaw = page.evaluate(
                "() => [...document.querySelectorAll('#traces-filter input[name=type]:checked')].map(cb => cb.value)");
        @SuppressWarnings("unchecked")
        List<String> checkedTypes = (List<String>) checkedTypesRaw;
        assertThat(checkedTypes).containsExactly("SCHEDULED_JOB");
    }

    /**
     * Every URL-sourced filter value traces.js reads is validated, "bucket" included: seeding
     * currentBucket='bogus' verbatim from "#traces?bucket=bogus" would hit the backend with it
     * and (on an empty result) render BUCKET_EMPTY_MESSAGES's literal "undefined" as the
     * empty-state text, since no such key exists. seedFromUrl falls back to 'all' for
     * anything not in BUCKET_EMPTY_MESSAGES's own key set - proven here
     * by the bucket strip itself: an unvalidated 'bogus' would match none of the three real
     * bucket buttons, leaving all three unpressed, where the fallback leaves "All" pressed.
     */
    @Test
    void bogusBucketInTheUrlFallsBackToAllInsteadOfHittingTheBackendWithIt() {
        String traceId = seedAnHttpTrace();
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?bucket=bogus");
        dashboard.awaitListedTrace(traceId);

        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='all']", "aria-pressed"))
                .isEqualTo("true");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='errors']", "aria-pressed"))
                .isEqualTo("false");
        assertThat(page.getAttribute("#traces-bucket .pk-btn[data-bucket='slow']", "aria-pressed"))
                .isEqualTo("false");
    }

    /**
     * The scheduled-tasks "view traces" link pre-filters the Traces tab to that
     * scheduler's own SCHEDULED_JOB traces (rootActionType + rootOperation): a plain
     * "#traces?type=...&op=..." href the hash router lands on, restored by traces.js's own
     * URL reconciliation. Proves the link actually arrives filtered, not just that it
     * switches tabs.
     */
    @Test
    void schedulerTracesLinkArrivesFiltered() {
        openDashboard();
        dashboard.openTab("scheduled-tasks");
        page.click("#scheduled-tasks-groups .pk-group__header");
        page.waitForSelector(".pk-task__traces-link");

        page.click(".pk-task__traces-link");
        page.waitForSelector("#traces-tab.active");
        page.waitForFunction("() => !document.getElementById('traces-active-filter').classList.contains('hidden')");

        assertThat(page.textContent("#traces-active-filter")).contains("Type:").contains("Target:");
        assertThat(page.isChecked(TRACE_TYPE_CHIP + "[value='SCHEDULED_JOB']")).isTrue();
    }

    /**
     * Connection-pool traces sit in the store but not in the default view: with the
     * exclusion ticked and no type chosen, traces.js names no type in its request and the
     * backend answers with the default view. Pool maintenance has no type chip of its own;
     * unticking its exclusion asks for every type and lands in the URL, so the revealed
     * view stays shareable.
     */
    @Test
    void connectionPoolTracesAreExcludedByDefaultAndRevealedByUntickingTheirExclusion() throws SQLException {
        // What HikariCP maintenance does: acquire a pooled connection outside any traced
        // work, yielding a standalone CONNECTION_POOL trace.
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }
        // the connection trace is the one the default view must hide, so the list needs a
        // trace of this test's own to render before the absence means anything
        String httpTraceId = seedAnHttpTrace();

        openDashboard();
        Response defaultResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.click(Dashboard.tabButton("traces")));
        assertThat(rootActionTypeOf(defaultResponse)).isNull();
        dashboard.awaitListedTrace(httpTraceId);
        assertThat(page.locator(CONNECTION_POOL_ROW_ICON).count()).isZero();
        assertThat(page.isChecked(CONNECTION_POOL_EXCLUSION)).isTrue();
        assertThat(page.locator(TRACE_TYPE_CHIP + "[value='CONNECTION_POOL']").count())
                .isZero();

        Response revealedResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.uncheck(CONNECTION_POOL_EXCLUSION));

        assertThat(rootActionTypeOf(revealedResponse)).isEqualTo("*");
        assertThat(page.url()).endsWith("#traces?excluded=none");
        page.waitForSelector(CONNECTION_POOL_ROW_ICON);
    }

    /**
     * With a type chosen, unticking the exclusion adds pool traces to that type rather than
     * replacing it, and Clear filter restores the exclusion along with everything else.
     */
    @Test
    void untickingTheConnectionPoolExclusionAddsItToTheChosenTypes() {
        openDashboard();
        page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.click(Dashboard.tabButton("traces")));
        page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.check(TRACE_TYPE_CHIP + "[value='HTTP_REQUEST']"));

        Response combinedResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.uncheck(CONNECTION_POOL_EXCLUSION));

        assertThat(rootActionTypeOf(combinedResponse)).isEqualTo("HTTP_REQUEST,CONNECTION_POOL");
        assertThat(page.url()).endsWith("#traces?type=HTTP_REQUEST&excluded=none");
        // the banner follows the rendered response, which lands after the response itself
        page.waitForFunction(
                "() => document.querySelector('#traces-active-filter').textContent.includes('Connection Pool included')");
        assertThat(page.textContent("#traces-active-filter"))
                .contains("Type: HTTP |")
                .contains("Connection Pool included");

        Response clearedResponse = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"), () -> page.click("#traces-filter-clear"));

        assertThat(rootActionTypeOf(clearedResponse)).isNull();
        assertThat(page.isChecked(CONNECTION_POOL_EXCLUSION)).isTrue();
        assertThat(page.isChecked(TRACE_TYPE_CHIP + "[value='HTTP_REQUEST']")).isFalse();
        assertThat(page.url()).endsWith("#traces");
    }

    /** A shared link with the exclusion lifted arrives with the box unticked and every type asked for. */
    @Test
    void deepLinkRestoresTheLiftedConnectionPoolExclusion() {
        Response listing = page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#traces?excluded=none"));

        assertThat(rootActionTypeOf(listing)).isEqualTo("*");
        page.waitForFunction("() => !document.getElementById('traces-active-filter').classList.contains('hidden')");
        assertThat(page.isChecked(CONNECTION_POOL_EXCLUSION)).isFalse();
        assertThat(page.url()).endsWith("#traces?excluded=none");
    }

    /** Each chip shows its type's icon, kept out of the accessible name its label already gives. */
    @Test
    void traceFilterChipsShowTheirTypeIcon() {
        String httpIcon = (String) importModule("shared/root-actions.js", "m.rootActionIcon('HTTP_REQUEST')");
        String poolIcon = (String) importModule("shared/root-actions.js", "m.rootActionIcon('CONNECTION_POOL')");

        openDashboard();
        dashboard.openTab("traces");
        page.waitForSelector(CONNECTION_POOL_EXCLUSION);

        assertThat(page.textContent(
                        "#traces-filter label:has(input[name='type'][value='HTTP_REQUEST']) [aria-hidden='true']"))
                .isEqualTo(httpIcon);
        assertThat(page.textContent("#traces-filter label:has(" + CONNECTION_POOL_EXCLUSION + ") [aria-hidden='true']"))
                .isEqualTo(poolIcon);
        assertThat(page.getByRole(
                                AriaRole.CHECKBOX,
                                new Page.GetByRoleOptions().setName("HTTP").setExact(true))
                        .count())
                .isOne();
    }

    /**
     * Every type chip and the exclusion share one line on a desktop viewport. Clear filter
     * sits in the active-filter banner instead, which is what leaves the row room.
     */
    @Test
    void traceFilterRowFitsOneLineOnADesktopViewport() {
        page.setViewportSize(1280, 900);
        openDashboard();
        dashboard.openTab("traces");
        page.waitForResponse(
                response -> response.url().contains("/api/traces/insights"),
                () -> page.check(TRACE_TYPE_CHIP + "[value='HTTP_REQUEST']"));
        page.waitForSelector("#traces-active-filter:not(.hidden) #traces-filter-clear");

        @SuppressWarnings("unchecked")
        List<Number> bottoms = (List<Number>) page.evaluate("() => [...document.querySelectorAll("
                + "'#traces-filter > :not(.hidden), #traces-filter-excluded > *')]"
                + ".map(el => el.getBoundingClientRect().bottom)");
        double firstBottom = bottoms.getFirst().doubleValue();
        assertThat(bottoms)
                .as("every control's bottom edge on the first control's line")
                .allSatisfy(bottom -> assertThat(bottom.doubleValue()).isLessThan(firstBottom + 12));
    }

    /** The type names traces.js asked the listing endpoint for, or null when it named none. */
    private static String rootActionTypeOf(Response listing) {
        Matcher matcher = ROOT_ACTION_TYPE_PARAM.matcher(listing.url());
        return matcher.find() ? URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8) : null;
    }
}
