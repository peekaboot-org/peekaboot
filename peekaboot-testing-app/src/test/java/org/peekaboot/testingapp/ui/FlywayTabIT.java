package org.peekaboot.testingapp.ui;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Flyway is disabled under the H2 test profile (application-test.yml sets
 * flyway.enabled: false) because the migrations are PostgreSQL-specific. This runs
 * them against H2 in PostgreSQL compatibility mode instead, on their own in-memory
 * datasource, so the Flyway tab has real migration data to render. H2's PostgreSQL
 * mode accepts BIGSERIAL (the only PostgreSQL-specific syntax the migrations use) -
 * confirmed by this test passing - so no H2-compatible copy of the migrations was
 * needed.
 */
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:flywaydb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
            "spring.flyway.enabled=true",
            "spring.jpa.hibernate.ddl-auto=none"
        })
class FlywayTabIT extends PlaywrightTestBase {

    /** The versions of the {@code V<n>__*.sql} files this application ships, as the tab renders them. */
    private static String[] migrationVersions() throws Exception {
        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
        return Arrays.stream(migrations)
                .map(migration -> versionOf(requireNonNull(migration.getFilename())))
                .toArray(String[]::new);
    }

    private static String versionOf(String migrationFileName) {
        return migrationFileName.substring(0, migrationFileName.indexOf("__"));
    }

    @Test
    void flywayTabListsEveryMigrationAsApplied() throws Exception {
        openDashboard();
        dashboard.openTab("flyway");

        assertThat(page.locator("#flyway-timeline .pk-table tbody tr .pk-badge").allTextContents())
                .as("a migration history with a failed or pending row is what this tab exists to show")
                .isNotEmpty()
                .containsOnly("SUCCESS");
        assertThat(page.textContent("#flyway-timeline"))
                .as("every migration on the classpath reached the schema history")
                .contains(migrationVersions());
    }

    /**
     * One row per migration, not one card per migration: a real schema history runs to
     * hundreds of migrations, so the row budget is what keeps the tab usable.
     */
    @Test
    void flywayTabRendersOneTableRowPerMigration() throws Exception {
        openDashboard();
        dashboard.openTab("flyway");

        assertThat(page.querySelectorAll("#flyway-timeline .pk-table thead th"))
                .as("the table is column-headed so each migration reads as a record")
                .hasSize(7);

        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*.sql");
        assertThat(page.querySelectorAll("#flyway-timeline .pk-table tbody tr"))
                .as("one table row per migration file on the classpath")
                .hasSize(migrations.length);
    }

    /** The Duration column reads each migration's execution time off the real response. */
    @Test
    void everyMigrationShowsItsExecutionTime() {
        openDashboard();
        dashboard.openTab("flyway");

        assertThat(page.locator("#flyway-timeline .pk-table tbody tr td:nth-child(5)")
                        .allTextContents())
                .isNotEmpty()
                .allMatch(duration -> duration.matches("<1ms|\\d+ms|\\d+\\.\\d{2}[sm]"));
    }

    /**
     * A migration's execution time is coloured by nothing. The span thresholds (100 ms and
     * 500 ms by default) describe request spans; a schema migration that takes seconds is
     * doing its job, and painting it in the danger colour made every real migration history
     * read as a page of problems.
     */
    @Test
    void migrationDurationsAreNotColouredBySpanThresholds() {
        Object durationCellClass = importModule("dashboard/tabs/flyway.js", """
            (() => {
                const container = document.createElement('div');
                container.innerHTML = '<div id="flyway-timeline"></div>';
                m.create(container).show({flyway: {migrations: [{version: '1', description: 'init',
                    script: 'V1__init.sql', type: 'SQL', executionTimeMs: 5000, installedOn: 0, state: 'SUCCESS'}]}}, {});
                return container.querySelector('td.pk-table__num').className;
            })()
            """);

        assertThat((String) durationCellClass).doesNotContain("slow");
    }

    /** A repeatable migration has no version, and the row says so where "Vnull" stood. */
    @Test
    @SuppressWarnings("unchecked")
    void aRepeatableMigrationIsNamedInsteadOfAVersion() {
        Object firstCells = importModule("dashboard/tabs/flyway.js", """
            (() => {
                const container = document.createElement('div');
                container.innerHTML = '<div id="flyway-timeline"></div>';
                m.create(container).show({flyway: {migrations: [
                    {version: null, description: 'views', script: 'R__views.sql', type: 'SQL',
                        executionTimeMs: 5, installedOn: 0, state: 'SUCCESS'},
                    {version: '1', description: 'init', script: 'V1__init.sql', type: 'SQL',
                        executionTimeMs: 5, installedOn: 0, state: 'SUCCESS'}]}}, {});
                return [...container.querySelectorAll('tbody tr td:first-child')].map(cell => cell.textContent);
            })()
            """);

        assertThat((List<String>) firstCells).containsExactly("Repeatable", "V1");
    }

    /** One migration, rendered three times: only the reader's locale and time zone put its row out of date. */
    @Test
    @SuppressWarnings("unchecked")
    void aLocaleOrTimeZoneSwitchRedrawsTheInstallDateOfAnUnchangedMigration() {
        Object installed = importModule("dashboard/tabs/flyway.js", """
            (() => {
                const container = document.createElement('div');
                container.innerHTML = '<div id="flyway-timeline"></div>';
                const tab = m.create(container);
                const installedFor = display => {
                    tab.show({flyway: {migrations: [{version: '1', description: 'init',
                        script: 'V1__init.sql', type: 'SQL', executionTimeMs: 5,
                        installedOn: '2026-01-15T12:00:00Z', state: 'SUCCESS'}]}}, display);
                    return container.querySelector('tbody tr td:nth-child(6)').textContent;
                };
                return [installedFor({locale: 'en-US', timeZone: 'UTC'}),
                    installedFor({locale: 'de-DE', timeZone: 'UTC'}),
                    installedFor({locale: 'de-DE', timeZone: 'Asia/Tokyo'})];
            })()
            """);

        assertThat((List<String>) installed).hasSize(3).doesNotHaveDuplicates();
    }

    /** From a second up an execution time is a decimal figure, so a locale switch rewrites it. */
    @Test
    @SuppressWarnings("unchecked")
    void aMigrationsExecutionTimeIsWrittenInTheDashboardLocale() {
        Object executionTimes = importModule("dashboard/tabs/flyway.js", """
            (() => {
                const container = document.createElement('div');
                container.innerHTML = '<div id="flyway-timeline"></div>';
                const tab = m.create(container);
                const executionTimeFor = display => {
                    tab.show({flyway: {migrations: [{version: '1', description: 'init',
                        script: 'V1__init.sql', type: 'SQL', executionTimeMs: 1500,
                        installedOn: '2026-01-15T12:00:00Z', state: 'SUCCESS'}]}}, display);
                    return container.querySelector('tbody tr td:nth-child(5)').textContent;
                };
                return [executionTimeFor({locale: 'en-US', timeZone: 'UTC'}),
                    executionTimeFor({locale: 'de-DE', timeZone: 'UTC'})];
            })()
            """);

        assertThat((List<String>) executionTimes).containsExactly("1.50s", "1,50s");
    }
}
