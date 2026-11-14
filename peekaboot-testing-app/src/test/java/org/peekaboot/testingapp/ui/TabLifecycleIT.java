package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The dashboard's tab lifecycle (dashboard/main.js): a tab is rendered while it is the one
 * showing and not otherwise, and a tab that cannot render says so in its own panel.
 */
class TabLifecycleIT extends PlaywrightTestBase {

    private static final String FAILURE_NOTE = "#environment-tab .pk-tab-failure";

    @Test
    void onlyTheTabThatIsShowingIsRendered() {
        openDashboard();

        assertThat(page.locator("#property-sources .pk-group").count()).isZero();
        assertThat(page.locator("#config-groups .pk-group").count()).isZero();

        dashboard.openTab("environment");

        assertThat(page.locator("#property-sources .pk-group").count()).isPositive();
        assertThat(page.locator("#config-groups .pk-group").count()).isZero();
    }

    @Test
    void aTabThatThrowsShowsItsOwnFailureAndLeavesTheOthersWorking() {
        servePropertySourcesTheEnvironmentTabCannotRender();
        openDashboard();

        page.click(Dashboard.tabButton("environment"));
        page.waitForSelector(FAILURE_NOTE);

        assertThat(page.textContent(FAILURE_NOTE)).startsWith("This tab could not be shown:");
        assertThat(page.isHidden("#error"))
                .as("the page banner is for a payload that failed to load")
                .isTrue();
        dashboard.openTab("config");
        assertThat(page.locator("#config-groups .pk-group").count()).isPositive();
    }

    @Test
    void aFailureNoteGoesWhenTheTabRendersAgain() {
        Runnable serveTheRealPayload = servePropertySourcesTheEnvironmentTabCannotRender();
        openDashboard();
        page.click(Dashboard.tabButton("environment"));
        page.waitForSelector(FAILURE_NOTE);
        dashboard.openTab("config");

        page.click(Dashboard.tabButton("environment"));
        page.waitForSelector(FAILURE_NOTE);
        page.click("#refresh-btn");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");
        assertThat(page.locator(FAILURE_NOTE).count())
                .as("notes after the tab failed twice in a row")
                .isEqualTo(1);

        serveTheRealPayload.run();
        page.click("#refresh-btn");
        page.waitForSelector("#property-sources .pk-group__header");

        assertThat(page.locator(FAILURE_NOTE).count()).isZero();
    }

    /**
     * The listing is held at its route until the reader has left the Traces tab. The page has
     * the answer once its body has finished and one more task has run.
     */
    @Test
    void anAnswerThatArrivesAfterItsTabWasLeftRendersNothing() {
        openDashboard();
        ParkedRequest listing = parkFirst("**/api/traces?*");
        page.click(Dashboard.tabButton("traces"));
        listing.awaitParked();
        dashboard.openTab("overview");

        Response answer = page.waitForResponse("**/api/traces?*", listing::resume);
        assertThat(answer.finished()).as("the listing's failure").isNull();
        page.evaluate("() => new Promise(resolve => setTimeout(resolve))");

        assertThat(page.locator(Dashboard.tabReadySelector("traces")).count())
                .as("trace rows or the empty note in the tab that was left")
                .isZero();
    }

    /**
     * Serves the real payload with a property source whose properties are a number, which the
     * Environment tab throws on, until the returned action runs.
     */
    private Runnable servePropertySourcesTheEnvironmentTabCannotRender() {
        return interceptUntilReleased("**/api/actuator/all/insights*", route -> {
            APIResponse response = route.fetch();
            ObjectNode insights = (ObjectNode) readJson(response.text());
            ((ObjectNode) insights.path("environment"))
                    .set("propertySources", readJson("[{\"name\": \"broken\", \"properties\": 7}]"));
            route.fulfill(new Route.FulfillOptions().setResponse(response).setBody(insights.toString()));
        });
    }
}
