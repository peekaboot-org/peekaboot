package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.lifecycle.LifecycleEventFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A freshly booted application has exactly one run - its own - so a test against it
 * cannot prove paging, a deployment badge, an unclean exit, or a downtime at all. This
 * class gives the application a real history before it boots, written by
 * {@link LifecycleHistoryFixture} (which documents the shape of every seeded run).
 *
 * <p>{@code @DynamicPropertySource} runs while the context is being prepared, before
 * {@code LifecycleEventLog.beginLoad()} ever reads the file - seeding from
 * {@code @BeforeAll} would race that read. {@code storageDir} has to be a static
 * {@code @TempDir} field for the same reason: the dynamic-property method is static and
 * runs before any instance (or {@code @BeforeAll}) exists.
 *
 * <p>The context is closed with the class ({@code @DirtiesContext}): storage is on, so its
 * insights snapshot writer keeps writing into {@code storageDir} on a cadence and once more
 * at shutdown, and a context left in the cache would outlive the temp directory - every
 * later write, the shutdown one included, would land in a deleted directory and WARN. The
 * context is this class's own anyway (the property source names a directory nobody else
 * gets), so closing it costs no other class a re-boot.
 *
 * <p>45 seeded runs plus the application's own start is 46 runs = 3 pages of 20, so
 * paging is real rather than a single page pretending to be several. Run
 * {@value #UNCLEAN_INDEX} is the unclean one, run {@value #VERSION_CHANGE_INDEX} the version
 * change and run {@value #BRANCH_CHANGE_INDEX} the branch and commit change; the whole
 * seeded span is about 6 days (45 * (2h30m + 45m)), finishing roughly 24 days before the
 * application's own start.
 *
 * <p>Response rows are newest first: index 0 is the application's own (real) run, and
 * seeded run {@code i} lands at response index {@code 45 - i}. That places every case
 * above (indices 1, 4, 5, 10, 15) on page 1, so the badge/dash assertions don't need to
 * page-navigate to find them - paging itself is proven separately.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LifecycleTabIT extends PlaywrightTestBase {

    private static final String ROWS = "#lifecycle-runs .pk-lifecycle-table tbody tr";

    private static final int SEEDED_RUN_COUNT = 45;
    private static final int UNCLEAN_INDEX = 40;
    private static final int VERSION_CHANGE_INDEX = 35;
    private static final int BRANCH_CHANGE_INDEX = 30;

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void lifecycleStorage(DynamicPropertyRegistry registry) {
        new LifecycleHistoryFixture(SEEDED_RUN_COUNT, UNCLEAN_INDEX, VERSION_CHANGE_INDEX, BRANCH_CHANGE_INDEX)
                .writeTo(storageDir.resolve(LifecycleEventFile.FILE_NAME));
        registry.add("peekaboot.storage.enabled", () -> "true");
        registry.add("peekaboot.storage.dir", () -> storageDir.toString());
    }

    @BeforeEach
    void captureBrowserConsole() {
        captureBrowserSignals();
    }

    private void openLifecycle() {
        openDashboard();
        page.click("#lifecycle-tab-btn");
        page.waitForSelector(ROWS);
    }

    private Locator pagerButton(int index) {
        return page.locator(".pk-lifecycle-pager button").nth(index);
    }

    private Locator row(int index) {
        return page.locator(ROWS).nth(index);
    }

    @Test
    void tabAppearsInTheStripAndOpens() {
        openDashboard();

        assertThat(page.isVisible("#lifecycle-tab-btn")).isTrue();
        page.click("#lifecycle-tab-btn");

        page.waitForSelector("#lifecycle-tab.active");
        page.waitForSelector(ROWS);
    }

    @Test
    void firstPageShowsTwentyRowsWithPreviousDisabled() {
        openLifecycle();

        assertThat(page.querySelectorAll(ROWS)).hasSize(20);
        assertThat(page.textContent(".pk-lifecycle-pager__readout")).isEqualTo("Page 1 of 3");
        assertThat(pagerButton(0).isDisabled()).as("Previous on page 1").isTrue();
        assertThat(pagerButton(1).isDisabled()).as("Next on page 1").isFalse();
    }

    @Test
    void nextWalksThroughAllThreePagesAndDisablesAtTheEnds() {
        openLifecycle();

        pagerButton(1).click();
        page.waitForFunction(
                "() => document.querySelector('.pk-lifecycle-pager__readout')?.textContent === 'Page 2 of 3'");
        assertThat(page.querySelectorAll(ROWS)).hasSize(20);
        assertThat(pagerButton(0).isDisabled()).as("Previous on page 2").isFalse();
        assertThat(pagerButton(1).isDisabled()).as("Next on page 2").isFalse();

        pagerButton(1).click();
        page.waitForFunction(
                "() => document.querySelector('.pk-lifecycle-pager__readout')?.textContent === 'Page 3 of 3'");
        assertThat(page.querySelectorAll(ROWS)).as("the remaining 6 of 46 runs").hasSize(6);
        assertThat(pagerButton(0).isDisabled()).as("Previous on page 3").isFalse();
        assertThat(pagerButton(1).isDisabled()).as("Next on page 3").isTrue();
    }

    @Test
    void newestRunIsTheApplicationsOwnAndCarriesTheRunningBadge() {
        openLifecycle();

        assertThat(row(0).locator(".pk-badge--ok").textContent()).isEqualTo("Running");
    }

    @Test
    void uncleanRunShowsItsBadgeAndADashDuration() {
        openLifecycle();

        Locator uncleanRow = row(5); // seeded run UNCLEAN_INDEX (40) -> response index 45 - 40
        assertThat(uncleanRow.locator(".pk-badge--error").textContent()).isEqualTo("Unclean exit");
        assertThat(uncleanRow.locator("td").nth(1).textContent())
                .as("we do not know when an unclean run died - never a computed guess")
                .isEqualTo("-");
    }

    @Test
    void theRunAfterAnUncleanExitHasAnUnknowableDowntime() {
        openLifecycle();

        Locator followingRow = row(4); // seeded run 41, started right after the unclean run 40
        assertThat(followingRow.locator("td").nth(3).textContent())
                .as("its preceding event is a start, not a stop, so the gap is unknowable")
                .isEqualTo("-");
    }

    @Test
    void versionChangeIsFlaggedAsADeployment() {
        openLifecycle();

        Locator versionRow = row(10); // seeded run VERSION_CHANGE_INDEX (35) -> response index 45 - 35
        assertThat(versionRow.locator(".pk-badge--info").textContent())
                .contains("Deployment")
                .contains("version");
    }

    @Test
    void branchAndCommitChangeAreFlaggedAsADeployment() {
        openLifecycle();

        Locator branchRow = row(15); // seeded run BRANCH_CHANGE_INDEX (30) -> response index 45 - 30
        assertThat(branchRow.locator(".pk-badge--info").textContent())
                .contains("Deployment")
                .contains("branch")
                .contains("commit");
    }

    @Test
    void completedRunShowsARealFormattedDuration() {
        openLifecycle();

        Locator mostRecentSeededRun = row(1); // seeded run 44, a clean 2h30m run
        assertThat(mostRecentSeededRun.locator("td").nth(1).textContent()).isEqualTo("2 hours, 30 minutes");
    }
}
