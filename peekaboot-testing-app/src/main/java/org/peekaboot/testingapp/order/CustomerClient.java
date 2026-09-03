package org.peekaboot.testingapp.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Calls this application's own person API so every orders trace contains a genuine
 * outbound CLIENT span next to its database spans.
 *
 * <p>Built from the auto-configured {@code RestClient.Builder} on purpose - that builder
 * carries Spring Boot's observation instrumentation. A plain {@code RestClient.create()}
 * would issue the same request and produce no span at all.
 */
@Component
public class CustomerClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerClient.class);

    private final RestClient restClient;
    private final LocalPortSupplier localPort;


    public CustomerClient(RestClient.Builder restClientBuilder, LocalPortSupplier localPort) {

        this.restClient = restClientBuilder.build();
        this.localPort = localPort;
    }


    public String lookupCustomerName(long customerId) {

        try {
            JsonNode person = restClient.get()
                    .uri("http://localhost:{port}/api/person/{id}", localPort.port(), customerId)
                    .retrieve()
                    .body(JsonNode.class);

            if (person == null || person.path("firstName").isMissingNode()) {
                return "customer #" + customerId;
            }
            return person.path("firstName").asString("") + " " + person.path("lastName").asString("");
        } catch (RuntimeException e) {
            log.warn("customer lookup for {} failed, falling back to the id", customerId, e);
            return "customer #" + customerId;
        }
    }
}
