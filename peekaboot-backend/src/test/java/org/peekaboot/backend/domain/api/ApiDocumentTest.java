package org.peekaboot.backend.domain.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ApiDocumentTest {

    private final ApiDocument bundled = ApiDocument.bundled();

    @Test
    void theBackendJarCarriesAnOpenApi31DocumentThatDescribesItself() {
        JsonNode document = bundled.forServer("").content();

        assertThat(document.path("openapi").asString()).isEqualTo("3.1.1");
        assertThat(document.path("paths").propertyNames()).contains(ApiDocument.DOCUMENT_PATH);
    }

    @Test
    void aCopyNamesItsRootAsItsOnlyServer() {
        assertThat(bundled.forServer("/ctx/app").content().path("servers").toString())
                .isEqualTo("[{\"url\":\"/ctx/app\"}]");
    }

    /** An empty server URL would resolve against the document's own location. */
    @Test
    void theServerIsTheRootWhereNothingPrefixesTheApi() {
        assertThat(bundled.forServer("").content().path("servers").toString()).isEqualTo("[{\"url\":\"/\"}]");
    }

    /** Every request is served from the one document read at startup, so each copy stands alone. */
    @Test
    void eachCopyKeepsItsOwnServer() {
        OpenApiDocument first = bundled.forServer("/a");
        bundled.forServer("/b");

        assertThat(first.content().path("servers").path(0).path("url").asString())
                .isEqualTo("/a");
    }

    @Test
    void theIndexListsEveryPathBehindItsRootWithItsSummary() {
        ApiIndex index = bundled.index("/ctx");

        assertThat(index.openapi()).isEqualTo("/ctx/peekaboot/api/openapi.json");
        assertThat(index.endpoints())
                .hasSize(bundled.forServer("").content().path("paths").size())
                .first()
                .isEqualTo(new ApiIndex.Endpoint(
                        "/ctx/peekaboot/api",
                        "Lists every endpoint with one line on what it is for, and where the OpenAPI document is."));
        assertThat(index.endpoints()).allSatisfy(endpoint -> {
            assertThat(endpoint.path()).startsWith("/ctx/peekaboot/api");
            assertThat(endpoint.purpose()).isNotBlank();
        });
    }
}
