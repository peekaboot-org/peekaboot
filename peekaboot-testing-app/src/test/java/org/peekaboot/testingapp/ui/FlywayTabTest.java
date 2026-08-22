package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

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
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:flywaydb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=none"
})
class FlywayTabTest extends PlaywrightTestBase {

    @Test
    void flywayTabListsAppliedMigrations() {
        openDashboard();
        page.click(".pk-tab[data-tab='flyway']");
        page.waitForSelector("#flyway-timeline .pk-table tbody tr");

        assertThat(page.textContent("#flyway-timeline")).contains("V1");
        assertThat(page.querySelectorAll("#flyway-timeline .pk-badge--ok")).isNotEmpty();
    }

    /**
     * One row per migration, not one card per migration: a real schema history runs to
     * hundreds of migrations, so the row budget is what keeps the tab usable.
     */
    @Test
    void flywayTabRendersOneTableRowPerMigration() {
        openDashboard();
        page.click(".pk-tab[data-tab='flyway']");
        page.waitForSelector("#flyway-timeline .pk-table tbody tr");

        assertThat(page.querySelectorAll("#flyway-timeline .pk-table thead th"))
                .as("the table is column-headed so each migration reads as a record")
                .hasSize(7);
        assertThat(page.querySelectorAll("#flyway-timeline .pk-table tbody tr"))
                .as("V1 and V2 are the two migrations this profile applies")
                .hasSize(2);
    }
}
