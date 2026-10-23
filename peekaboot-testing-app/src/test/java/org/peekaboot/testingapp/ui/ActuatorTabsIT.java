package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class ActuatorTabsIT extends PlaywrightTestBase {

    @Autowired
    private ScheduledTaskHolder scheduledTaskHolder;

    /**
     * Filtering never auto-expands a group - a matching group renders collapsed just like an
     * unfiltered one, so the header must be clicked open before a {@code <mark>} inside its
     * list becomes visible. That click is also the group's own collapse/expand contract, which
     * is why the collapsed state is asserted on the way in.
     */
    @Test
    void environmentFilterHighlightsMatchesInsideAGroupTheReaderOpens() {
        openDashboard();
        dashboard.openTab("environment");

        page.fill("#env-filter", "server.port");
        page.waitForSelector(
                "#property-sources mark", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.ATTACHED));
        assertThat(page.isVisible("#property-sources .pk-group__list")).isFalse();

        page.click("#property-sources .pk-group__header");

        page.waitForSelector("#property-sources mark");
        assertThat(page.isVisible("#property-sources .pk-group__list")).isTrue();
        assertThat(page.textContent("#property-sources mark")).contains("server.port");
    }

    @Test
    void expandedGroupSurvivesARefresh() {
        openDashboard();
        dashboard.openTab("config");
        page.click("#config-groups .pk-group__header");
        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();

        page.click("#refresh-btn");
        page.waitForFunction("() => !document.getElementById('refresh-icon').classList.contains('pk-spinning')");

        assertThat(page.isVisible("#config-groups .pk-group__list")).isTrue();
    }

    @Test
    void loggersTabShowsLevelsAndRespectsConfiguredOnly() {
        openDashboard();
        dashboard.openTab("loggers");

        int rows = page.locator("#loggers-list .pk-kv").count();
        assertThat(page.locator("#loggers-list .pk-kv .pk-badge").count())
                .as("every logger row renders its effective level, not just its name")
                .isEqualTo(rows);

        int all = page.querySelectorAll("#loggers-list .pk-group").size();
        page.check("#loggers-configured-only");
        page.waitForFunction("(prev) => document.querySelectorAll('#loggers-list .pk-group').length !== prev", all);
        int configured = page.querySelectorAll("#loggers-list .pk-group").size();

        assertThat(configured).isLessThan(all);
        assertThat(page.locator("#loggers-list .pk-kv__key").allTextContents())
                .as("ROOT always carries a configured level, so the filtered list keeps it")
                .contains("ROOT");
    }

    /** Logger packages take the strong name variant and metric names the mono one; property groups keep the plain one. */
    @Test
    void eachGroupTabNamesItsGroupsInItsOwnVariant() {
        openDashboard();
        assertThat(groupNameClasses("loggers", "#loggers-list")).containsOnly("pk-group__name pk-group__name--strong");
        assertThat(groupNameClasses("meters", "#meters-list")).containsOnly("pk-group__name pk-group__name--mono");
        assertThat(groupNameClasses("environment", "#property-sources")).containsOnly("pk-group__name");
    }

    private List<String> groupNameClasses(String tab, String list) {
        dashboard.openTab(tab);
        List<String> classes = page.locator(list + " .pk-group__name").all().stream()
                .map(name -> name.getAttribute("class"))
                .toList();
        assertThat(classes).as("the %s tab renders groups", tab).isNotEmpty();
        return classes;
    }

    @Test
    void scheduledTasksTabGroupsByScheduleType() {
        openDashboard();
        dashboard.openTab("scheduled-tasks");

        assertThat(page.locator("#scheduled-tasks-groups .pk-group__name").allTextContents())
                .as("a group per schedule type the app actually registers, and no other")
                .isNotEmpty()
                .isSubsetOf("Cron Tasks", "Fixed Delay Tasks", "Fixed Rate Tasks");
        assertThat(page.locator("#scheduled-tasks-groups .pk-count-summary .pk-badge")
                        .first()
                        .textContent())
                .as("the summary counts the tasks Spring registered, not the rows that rendered")
                .isEqualTo("Total: " + scheduledTaskHolder.getScheduledTasks().size());
    }

    /**
     * A task whose last run threw shows the exception beside its FAILED badge. The sample app
     * has a failing job, but nothing records an outcome for a run fired outside the scheduler,
     * so the row is rendered from the payload shape the backend would send.
     */
    @Test
    void aFailedTaskShowsItsStatusAndTheExceptionFromTheLastRun() {
        importModule("dashboard/tabs/scheduled-tasks.js", """
            (() => {
                const container = document.createElement('div');
                container.innerHTML = '<div id="scheduled-tasks-groups"></div>';
                container.id = 'pk-tasks-test-container';
                document.body.appendChild(container);
                m.render(container, {scheduledTasks: {tasks: [{target: 'demo.Job.run', type: 'FIXED_RATE',
                    intervalMs: 60000, lastStatus: 'FAILED', lastExecution: 0,
                    lastException: 'java.lang.IllegalStateException: fixedDelay failed'}],
                    cronCount: 0, fixedDelayCount: 0, fixedRateCount: 1}}, {});
            })()
            """);

        assertThat(page.textContent("#pk-tasks-test-container .pk-badge--error"))
                .isEqualTo("FAILED");
        assertThat(page.textContent("#pk-tasks-test-container .pk-task__exception"))
                .contains("Error during last Execution:")
                .contains("fixedDelay failed");
    }
}
