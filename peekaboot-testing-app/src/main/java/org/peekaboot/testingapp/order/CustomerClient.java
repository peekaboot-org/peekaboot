package org.peekaboot.testingapp.order;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
            // An unknown id answers 404, which RestClient raises, so "there is no such person"
            // is a catch below rather than a branch on the body.
            JsonNode person = requireNonNull(
                    restClient
                            .get()
                            .uri("http://localhost:{port}/api/person/{id}", localPort.port(), customerId)
                            .retrieve()
                            .body(JsonNode.class),
                    "the person API answered with no body");

            return person.path("firstName").asString("") + " "
                    + person.path("lastName").asString("");
        } catch (HttpClientErrorException.NotFound noSuchCustomer) {
            // An id with nobody behind it is an answer, not a failure - the order still renders.
            return "customer #" + customerId;
        } catch (RuntimeException e) {
            log.warn("customer lookup for {} failed, falling back to the id", customerId, e);
            return "customer #" + customerId;
        }
    }
}
