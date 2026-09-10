package org.peekaboot.testingapp.ui;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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
                m.render(container, {flyway: {migrations: [{version: '1', description: 'init',
                    script: 'V1__init.sql', type: 'SQL', executionTime: 5000, installedOn: 0, state: 'SUCCESS'}]}}, {});
                return container.querySelector('td.pk-table__num').className;
            })()
            """);

        assertThat((String) durationCellClass).doesNotContain("slow");
    }
}
