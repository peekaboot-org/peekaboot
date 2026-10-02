package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With Liquibase off there is no endpoint to read, so the strip hides the tab, as it does for Flyway. */
@TestPropertySource(properties = "spring.liquibase.enabled=false")
class LiquibaseTabHiddenIT extends PlaywrightTestBase {

    @Test
    void withoutChangeSetsTheTabIsHiddenAndADeepLinkFallsBackToOverview() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#liquibase");
        // Attached, not visible: Overview's build info renders in the same task as the fallback, whichever tab shows.
        page.waitForSelector(
                "#build-info > *", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));

        assertThat(page.url()).endsWith("#overview");
        assertThat(page.isVisible("#overview-tab")).isTrue();
        assertThat(page.isVisible(Dashboard.tabButton("liquibase"))).isFalse();
        assertThat(page.isVisible("#liquibase-tab")).isFalse();
    }
}
