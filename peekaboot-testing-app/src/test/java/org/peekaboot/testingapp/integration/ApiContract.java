package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.keyword.NonValidationKeyword;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI document the running application serves, and the check that a real answer is one it
 * describes. The status must be documented for that path, under the answer's content type, and
 * the body must validate against the schema named there. networknt's validator reads the schemas
 * in the OpenAPI 3.1 dialect with format assertions on, so an instant that is no RFC 3339
 * date-time fails too.
 */
final class ApiContract {

    static final String INDEX_PATH = "/peekaboot/api";

    static final String DOCUMENT_PATH = INDEX_PATH + "/openapi.json";

    /** The served copy is registered under this name, so no {@code $ref} in it ever leaves the process. */
    private static final String DOCUMENT_IRI = "https://peekaboot.invalid/peekaboot/api/openapi.json";

    private static final String SCHEMAS = "#/components/schemas/";

    /** The validator reads the whole document as a schema and warns once about every top-level field it does not know. */
    private static final Dialect OPENAPI_DOCUMENT = Dialect.builder(Dialects.getOpenApi31())
            .keywords(Stream.of("openapi", "jsonSchemaDialect", "info", "servers", "security", "paths", "components")
                    .map(NonValidationKeyword::new)
                    .toList())
            .build();

    private final PeekabootApi api;

    private final JsonNode document;

    private final SchemaRegistry schemas;

    /** One answer as served, whatever its status. */
    record Exchange(int status, @Nullable MediaType contentType, String body) {

        JsonNode json() {
            return PeekabootApi.readJson(body);
        }
    }

    private ApiContract(PeekabootApi api, String served) {
        this.api = api;
        this.document = PeekabootApi.readJson(served);
        this.schemas = SchemaRegistry.withDefaultDialect(
                OPENAPI_DOCUMENT,
                registry -> registry.schemas(Map.of(DOCUMENT_IRI, served))
                        .schemaRegistryConfig(SchemaRegistryConfig.builder()
                                .formatAssertionsEnabled(true)
                                .locale(Locale.ENGLISH)
                                .build()));
    }

    /** The document {@code api} serves, read for the checks below. */
    static ApiContract servedBy(PeekabootApi api) {
        return new ApiContract(api, api.get(DOCUMENT_PATH));
    }

    JsonNode document() {
        return document;
    }

    /** A GET that asks for JSON, answered whatever its status. */
    Exchange fetch(String uri) {
        return exchange(api, uri);
    }

    static Exchange exchange(PeekabootApi api, String uri) {
        return api.restClient()
                .get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Exchange(
                        response.getStatusCode().value(),
                        response.getHeaders().getContentType(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    /** {@code exchange} is an answer the document describes for GET {@code pathTemplate}. */
    void assertMatches(String pathTemplate, Exchange exchange) {
        MediaType contentType = exchange.contentType();
        assertThat(contentType)
                .as("the content type of the %d from GET %s", exchange.status(), pathTemplate)
                .isNotNull();
        assertMatches(
                pathTemplate,
                exchange.status(),
                contentType.getType() + "/" + contentType.getSubtype(),
                exchange.body());
    }

    /** {@code body}, a 200 a test already read as JSON, is one the document describes for GET {@code pathTemplate}. */
    void assertOk(String pathTemplate, JsonNode body) {
        assertMatches(pathTemplate, 200, MediaType.APPLICATION_JSON_VALUE, body.toString());
    }

    /** {@code json} validates against {@code #/components/schemas/<schemaName>}, for an event stream's data. */
    void assertSchemaMatches(String schemaName, String json) {
        assertValid(SCHEMAS + schemaName, json, schemaName);
    }

    /** The response GET {@code pathTemplate} documents for {@code status}, its {@code $ref} followed. */
    JsonNode responseOf(String pathTemplate, int status) {
        JsonNode operation = document.path("paths").path(pathTemplate).path("get");
        assertThat(operation.isObject())
                .as("the document describes GET %s", pathTemplate)
                .isTrue();
        JsonNode response = resolved(operation.path("responses").path(String.valueOf(status)));
        assertThat(response.isObject())
                .as("GET %s documents a %d", pathTemplate, status)
                .isTrue();
        return response;
    }

    /** {@code node} itself, or the node its {@code $ref} names in this document. */
    JsonNode resolved(JsonNode node) {
        JsonNode ref = node.path("$ref");
        return ref.isString() ? document.at(ref.asString().substring(1)) : node;
    }

    private void assertMatches(String pathTemplate, int status, String mediaType, String body) {
        JsonNode ref = responseOf(pathTemplate, status)
                .path("content")
                .path(mediaType)
                .path("schema")
                .path("$ref");
        assertThat(ref.isString())
                .as("GET %s documents its %d as %s", pathTemplate, status, mediaType)
                .isTrue();
        assertValid(ref.asString(), body, "the " + status + " from GET " + pathTemplate);
    }

    private void assertValid(String ref, String json, String what) {
        List<Error> errors =
                schemas.getSchema(SchemaLocation.of(DOCUMENT_IRI + ref)).validate(json, InputFormat.JSON);
        assertThat(errors).as("%s against %s", what, ref).isEmpty();
    }
}
