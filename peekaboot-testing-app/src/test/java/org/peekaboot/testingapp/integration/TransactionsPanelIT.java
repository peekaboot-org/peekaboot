package org.peekaboot.testingapp.integration;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The Transactions panel samples hibernate.transactions, which this app gets from Peekaboot's own
 * binder: it carries no hibernate-micrometer, like most JPA applications.
 */
class TransactionsPanelIT extends TestingAppITBase {

    /** /persons reads through a transactional Spring Data repository, so each call is one successful transaction. */
    @Test
    void theTransactionsPanelCarriesDataAfterATransactionalRequest() {
        traces.get("/persons");

        // level 0 ticks every 250ms in the test profile; the rate needs two samples either side of the request
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> successRates().valueStream().anyMatch(rate -> rate.asDouble() > 0));
    }

    private JsonNode successRates() {
        return api.getJson("/peekaboot/api/insights/data?level=0")
                .path("series")
                .path("transactions.success")
                .path("values");
    }
}
