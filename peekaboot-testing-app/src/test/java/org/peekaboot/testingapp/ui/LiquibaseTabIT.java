package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Liquibase tab: one table row per change set, in the order the backend sorted them. */
class LiquibaseTabIT extends PlaywrightTestBase {

    /** Two change sets, the second FAILED, rendered into a fresh {@code container} the expression then reads. */
    private static final String RENDER_TWO_CHANGE_SETS = """
            const container = document.createElement('div');
            container.innerHTML = '<div id="liquibase-changesets"></div>';
            m.render(container, {liquibase: {changeSets: [
                {orderExecuted: 1, id: 'create-product', author: 'peekaboot',
                    changeLog: 'db/changelog/inventory/db.changelog-master.yaml',
                    description: 'createTable tableName=product', dateExecuted: 0, execType: 'EXECUTED'},
                {orderExecuted: 2, id: 'seed-products', author: 'peekaboot',
                    changeLog: 'db/changelog/inventory/db.changelog-master.yaml',
                    description: 'sql', dateExecuted: 0, execType: 'FAILED'}
            ]}}, {});
            """;

    @Test
    void theTableIsColumnHeadedAndKeepsTheFullChangelogPathInTheTitle() {
        Object result = importModule("dashboard/tabs/liquibase.js", "(() => {" + RENDER_TWO_CHANGE_SETS + """
                return [
                    Array.from(container.querySelectorAll('thead th')).map(th => th.textContent),
                    container.querySelector('tbody tr .pk-liquibase-row__changelog').title
                ];
            })()
            """);

        assertThat(result)
                .isEqualTo(List.of(
                        List.of("Order", "ID", "Author", "Changelog", "Description", "Executed", "Status"),
                        "db/changelog/inventory/db.changelog-master.yaml"));
    }

    /** A change set with no execution date and no other fields: a dash for the date, blank cells and a muted badge. */
    @Test
    void aChangeSetWithMissingFieldsRendersBlankCellsAndADashedDate() {
        Object rows = importModule("dashboard/tabs/liquibase.js", """
                (() => {
                    const container = document.createElement('div');
                    container.innerHTML = '<div id="liquibase-changesets"></div>';
                    m.render(container, {liquibase: {changeSets: [
                        {orderExecuted: null, id: null, author: null, changeLog: null,
                            description: null, dateExecuted: null, execType: null},
                        {id: 'undated', dateExecuted: ''}
                    ]}}, {});
                    return Array.from(container.querySelectorAll('tbody tr')).map(row => [
                        Array.from(row.querySelectorAll('td')).map(td => td.textContent),
                        row.querySelector('.pk-badge').className
                    ]);
                })()
                """);

        assertThat(rows)
                .isEqualTo(List.of(
                        List.of(List.of("", "", "", "", "", "-", ""), "pk-badge pk-badge--muted"),
                        List.of(List.of("", "undated", "", "", "", "-", ""), "pk-badge pk-badge--muted")));
    }

    /** A failed change set is what this tab exists to surface: danger stripe and error badge, on no other row. */
    @Test
    void aFailedChangeSetGetsTheDangerStripeAndTheErrorBadge() {
        Object rows = importModule("dashboard/tabs/liquibase.js", "(() => {" + RENDER_TWO_CHANGE_SETS + """
                return Array.from(container.querySelectorAll('tbody tr')).map(row => [
                    row.classList.contains('pk-table__stripe--danger'),
                    row.querySelector('.pk-badge').className
                ]);
            })()
            """);

        assertThat(rows)
                .isEqualTo(List.of(List.of(false, "pk-badge pk-badge--ok"), List.of(true, "pk-badge pk-badge--error")));
    }

    /** The testing app has no Liquibase, so the strip hides the tab and a deep link lands on Overview, as for Flyway. */
    @Test
    void withoutChangeSetsTheTabIsHiddenAndADeepLinkFallsBackToOverview() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html#liquibase");
        page.waitForSelector("#overview-tab.active");
        page.waitForSelector("#build-info > *");

        assertThat(page.url()).endsWith("#overview");
        assertThat(page.isVisible(Dashboard.tabButton("liquibase"))).isFalse();
        assertThat(page.isVisible("#liquibase-tab")).isFalse();
    }
}
