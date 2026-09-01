package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.lifecycle.LifecycleEvent;
import org.peekaboot.backend.lifecycle.LifecycleEventFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A freshly booted application has exactly one run - its own - so a test against it
 * cannot prove paging, a deployment badge, an unclean exit, or a downtime at all. This
 * class gives the application a real history before it boots: {@link #seedLifecycleHistory}
 * writes a {@code lifecycle.jsonl} with the production {@link LifecycleEventFile} and
 * {@link LifecycleEvent}'s own factories - the fixture can never drift from the format
 * the reader expects, because if it did, every test below would fail instead of proving
 * nothing.
 *
 * <p>{@code @DynamicPropertySource} runs while the context is being prepared, before
 * {@code LifecycleEventLog.beginLoad()} ever reads the file - seeding from
 * {@code @BeforeAll} would race that read. {@code storageDir} has to be a static
 * {@code @TempDir} field for the same reason: the dynamic-property method is static and
 * runs before any instance (or {@code @BeforeAll}) exists.
 *
 * <p>45 seeded runs plus the application's own start is 46 runs = 3 pages of 20, so
 * paging is real rather than a single page pretending to be several. Chronologically
 * ascending, oldest first (index 0..44):
 * <ul>
 *   <li>every run ends cleanly with a 2h30m uptime and a 45m gap before the next start,
 *       except run {@value #UNCLEAN_INDEX};
 *   <li>run {@value #UNCLEAN_INDEX} has a start with no matching stop - a {@code kill -9} -
 *       so it renders an Unclean exit badge with a dash duration, and the run after it
 *       (whose preceding event is a start, not a stop) renders a dash downtime;
 *   <li>runs 0..{@value #VERSION_CHANGE_INDEX} exclusive carry version 1.0.0, the rest
 *       1.1.0, so run {@value #VERSION_CHANGE_INDEX} is flagged a deployment for its
 *       version change;
 *   <li>runs 0..{@value #BRANCH_CHANGE_INDEX} exclusive carry branch "main" and one commit,
 *       the rest "feature/redesign" and another, so run {@value #BRANCH_CHANGE_INDEX} is
 *       flagged a deployment for its branch and commit change.
 * </ul>
 *
 * <p>The whole seeded span is about 6 days (45 * (2h30m + 45m)), anchored 30 days before
 * "now", so it finishes roughly 24 days before the application's own (real-clock) start -
 * comfortably clear of any collision, and still the oldest-to-newest order the reader
 * expects.
 *
 * <p>Response rows are newest first: index 0 is the application's own (real) run, and
 * seeded run {@code i} lands at response index {@code 45 - i}. That places every case
 * above (indices 1, 4, 5, 10, 15) on page 1, so the badge/dash assertions don't need to
 * page-navigate to find them - paging itself is proven separately.
 */
class LifecycleTabIT extends PlaywrightTestBase {

    private static final String ROWS = "#lifecycle-runs .pk-lifecycle-table tbody tr";

    private static final int SEEDED_RUN_COUNT = 45;
    private static final int UNCLEAN_INDEX = 40;
    private static final int VERSION_CHANGE_INDEX = 35;
    private static final int BRANCH_CHANGE_INDEX = 30;
    private static final long RUN_DURATION_MS =
            Duration.ofHours(2).plusMinutes(30).toMillis();
    private static final long GAP_MS = Duration.ofMinutes(45).toMillis();

    @TempDir
    static Path storageDir;

    @DynamicPropertySource
    static void lifecycleStorage(DynamicPropertyRegistry registry) {
        seedLifecycleHistory(storageDir.resolve(LifecycleEventFile.FILE_NAME));
        registry.add("peekaboot.storage.enabled", () -> "true");
        registry.add("peekaboot.storage.dir", () -> storageDir.toString());
    }

    private static void seedLifecycleHistory(Path file) {
        long cursor = Instant.now().minus(30, ChronoUnit.DAYS).toEpochMilli();
        List<LifecycleEvent> events = new ArrayList<>();
        for (int i = 0; i < SEEDED_RUN_COUNT; i++) {
            long pid = 1000L + i;
            events.add(LifecycleEvent.start(cursor, pid, buildOf(i), gitOf(i)));
            cursor += RUN_DURATION_MS;
            if (i != UNCLEAN_INDEX) {
                events.add(LifecycleEvent.stop(cursor, pid));
            }
            cursor += GAP_MS;
        }
        try {
            new LifecycleEventFile(file).write(events);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to seed " + file, e);
        }
    }

    private static Map<String, String> buildOf(int i) {
        Map<String, String> build = new LinkedHashMap<>();
        build.put("version", i < VERSION_CHANGE_INDEX ? "1.0.0" : "1.1.0");
        return build;
    }

    private static Map<String, String> gitOf(int i) {
        Map<String, String> git = new LinkedHashMap<>();
        boolean deployed = i >= BRANCH_CHANGE_INDEX;
        git.put("branch", deployed ? "feature/redesign" : "main");
        git.put(
                "commit.id",
                deployed ? "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" : "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        return git;
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
