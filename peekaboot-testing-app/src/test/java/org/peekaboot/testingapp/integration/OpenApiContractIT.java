package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.testingapp.repository.OrderLineRepository;
import org.peekaboot.testingapp.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI document the API serves holds for the running application, over real HTTP. It
 * names every endpoint the application maps and every parameter each one takes, and every
 * answer validates against it.
 */
class OpenApiContractIT extends TestingAppITBase {

    /** The OpenAPI 3.1 schema for documents, checked in from spec.openapis.org. */
    private static final String OAS_SCHEMA_IRI = "https://spec.openapis.org/oas/3.1/schema/2022-10-07";

    private static final Pattern ACCEPTED = Pattern.compile("^Unknown parameter 'peekabootTypo'\\. Accepted: (.*)\\.$");

    /** A trace answers only once one is recorded, and the event stream never ends, so each has a test of its own. */
    private static final Set<String> VALIDATED_BY_THEIR_OWN_TESTS =
            Set.of("/peekaboot/api/traces/{traceId}", "/peekaboot/api/insights/stream");

    /** The running application's own mappings, so an endpoint added later is held to the document too. */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderLineRepository orderLineRepository;

    private ApiContract contract;

    @BeforeEach
    void readTheDocument() {
        contract = ApiContract.servedBy(api);
    }

    @Test
    void theDocumentDescribesEveryEndpointTheApplicationMapsAndNoOther() {
        assertThat(contract.document().path("paths").propertyNames())
                .containsExactlyInAnyOrderElementsOf(ApiMappings.paths(mappings));
    }

    @Test
    void everyEndpointAnswersGetAloneAsTheDocumentSays() {
        assertThat(ApiMappings.methods(mappings))
                .allSatisfy(methods -> assertThat(methods).containsExactly(RequestMethod.GET));
        assertThat(contract.document().path("paths").valueStream())
                .allSatisfy(path -> assertThat(path.propertyNames()).containsExactly("get"));
    }

    /**
     * The guard names what an endpoint takes in its refusal, read off the handler, so the
     * document's parameters are held to the code. The refusal itself is the 400 the document
     * describes.
     */
    @Test
    void everyOperationNamesTheParametersItsEndpointTakes() {
        for (String path : ApiMappings.paths(mappings)) {
            ApiContract.Exchange refusal = contract.fetch(ApiMappings.withUnknownParameter(path));
            assertThat(refusal.status()).as(path).isEqualTo(400);
            contract.assertMatches(path, refusal);

            Matcher accepted = ACCEPTED.matcher(refusal.json().path("detail").asString(""));
            assertThat(accepted.matches())
                    .as("the refusal of %s names what it takes", path)
                    .isTrue();
            List<String> taken = "none".equals(accepted.group(1))
                    ? List.of()
                    : List.of(accepted.group(1).split(", ", -1));
            assertThat(parameterNames(path, "query"))
                    .as("the query parameters of %s", path)
                    .containsExactlyElementsOf(taken);
            assertThat(parameterNames(path, "path"))
                    .as("the path parameters of %s", path)
                    .containsExactlyElementsOf(ApiMappings.templateVariables(path));
        }
    }

    @Test
    void everyOperationHasASummaryAndDescribesTheRefusalsEveryEndpointCanAnswer() {
        assertThat(contract.document().path("paths").propertyStream()).allSatisfy(path -> {
            JsonNode operation = path.getValue().path("get");
            assertThat(operation.path("summary").asString("")).as(path.getKey()).isNotBlank();
            assertThat(operation.path("responses").path("400").path("$ref").asString(""))
                    .as(path.getKey())
                    .isEqualTo("#/components/responses/BadRequest");
            assertThat(operation.path("responses").path("401").path("$ref").asString(""))
                    .as(path.getKey())
                    .isEqualTo("#/components/responses/Unauthorized");
        });
    }

    /**
     * The rules that make the validation below strict, so a field the code adds or drops fails it.
     * Every object schema lists every property as required and allows no other, no object or
     * array leaves open what it holds, every JSON body is a named schema, and every {@code $ref}
     * resolves.
     */
    @Test
    void theDocumentKeepsItsOwnRules() {
        JsonNode document = contract.document();
        Map<String, JsonNode> schemas = new LinkedHashMap<>();
        document.path("components")
                .path("schemas")
                .propertyStream()
                .forEach(schema -> collectSchemas(schema.getValue(), schema.getKey(), schemas));
        assertThat(schemas.entrySet().stream()
                        .filter(schema -> schema.getValue().has("properties")))
                .allSatisfy(schema -> {
                    assertThat(schema.getValue().path("additionalProperties").isBoolean()
                                    && !schema.getValue()
                                            .path("additionalProperties")
                                            .asBoolean())
                            .as("%s allows no other property", schema.getKey())
                            .isTrue();
                    assertThat(schema.getValue().path("required").valueStream().map(JsonNode::asString))
                            .as("%s requires every property it names", schema.getKey())
                            .containsExactlyElementsOf(
                                    schema.getValue().path("properties").propertyNames());
                });
        List<JsonNode> bodies = new ArrayList<>();
        document.path("paths")
                .valueStream()
                .flatMap(path -> path.path("get").path("responses").valueStream())
                .map(contract::resolved)
                .forEach(response -> {
                    bodies.add(response.path("content").path("application/json"));
                    bodies.add(response.path("content").path("application/problem+json"));
                });
        assertThat(bodies.stream().filter(JsonNode::isObject))
                .allSatisfy(body -> assertThat(body.path("schema").path("$ref").asString(""))
                        .startsWith("#/components/schemas/"));
        assertThat(schemas.entrySet().stream()
                        .filter(schema -> isHole(schema.getValue()))
                        .map(Map.Entry::getKey))
                .as("objects and arrays that say nothing of what they hold")
                .isEmpty();
        List<String> refs = new ArrayList<>();
        collectRefs(document, refs);
        assertThat(refs)
                .allSatisfy(ref -> assertThat(document.at(ref.substring(1)).isMissingNode())
                        .as("%s resolves", ref)
                        .isFalse());
    }

    @Test
    void theDocumentIsAnOpenApi31Document() throws IOException {
        String oasSchema = new ClassPathResource("openapi/oas-3.1-schema-2022-10-07.json")
                .getContentAsString(StandardCharsets.UTF_8);
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                Dialects.getDraft202012(), builder -> builder.schemas(Map.of(OAS_SCHEMA_IRI, oasSchema)));

        assertThat(registry.getSchema(SchemaLocation.of(OAS_SCHEMA_IRI))
                        .validate(contract.document().toString(), InputFormat.JSON))
                .isEmpty();
    }

    /** At the root, with neither a context path nor a DispatcherServlet prefix, the server is the root. */
    @Test
    void theDocumentNamesTheServerTheRequestReached() {
        assertThat(contract.document().path("servers").toString()).isEqualTo("[{\"url\":\"/\"}]");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "/peekaboot/api/openapi.json | /peekaboot/api/openapi.json",
                "/peekaboot/api/features | /peekaboot/api/features",
                "/peekaboot/api/traces | /peekaboot/api/traces",
                "/peekaboot/api/traces | /peekaboot/api/traces?view=full&rootActionType=*&limit=10000",
                "/peekaboot/api/logs | /peekaboot/api/logs?limit=10000",
                "/peekaboot/api/routes | /peekaboot/api/routes",
                "/peekaboot/api/errors | /peekaboot/api/errors",
                "/peekaboot/api/insights/config | /peekaboot/api/insights/config",
                "/peekaboot/api/insights/data | /peekaboot/api/insights/data?level=0",
                "/peekaboot/api/insights/data | /peekaboot/api/insights/data?level=0&view=full",
                "/peekaboot/api/lifecycle/events | /peekaboot/api/lifecycle/events",
                "/peekaboot/api/lifecycle/runs | /peekaboot/api/lifecycle/runs",
                "/peekaboot/api/actuator/all/insights | /peekaboot/api/actuator/all/insights",
                "/peekaboot/api/actuator/all/insights | /peekaboot/api/actuator/all/insights?locale=de-DE&unmask=true",
                "/peekaboot/api/metrics | /peekaboot/api/metrics"
            })
    void everyEndpointAnswersWhatTheDocumentDescribes(String pathTemplate, String uri) {
        ApiContract.Exchange answer = contract.fetch(uri);

        assertThat(answer.status()).isEqualTo(200);
        contract.assertMatches(pathTemplate, answer);
    }

    @Test
    void anUnknownTraceIsAProblemTheDocumentDescribes() {
        ApiContract.Exchange answer = contract.fetch("/peekaboot/api/traces/00000000000000000000000000000000");

        assertThat(answer.status()).isEqualTo(404);
        contract.assertMatches("/peekaboot/api/traces/{traceId}", answer);
    }

    /** An endpoint added later fails here until a row above, or a test of its own, validates its answer. */
    @Test
    void everyEndpointHasItsAnswerValidated() throws NoSuchMethodException {
        Set<String> validated = new HashSet<>(VALIDATED_BY_THEIR_OWN_TESTS);
        Arrays.stream(OpenApiContractIT.class
                        .getDeclaredMethod("everyEndpointAnswersWhatTheDocumentDescribes", String.class, String.class)
                        .getAnnotation(CsvSource.class)
                        .value())
                .map(row -> row.substring(0, row.indexOf('|')).strip())
                .forEach(validated::add);

        assertThat(validated).containsExactlyInAnyOrderElementsOf(ApiMappings.paths(mappings));
    }

    /** A failure with its stack trace, an N+1, async work, a slow query, a client error and a plain request, each as one trace and in the lists. */
    @Test
    void tracesOfEveryKindAnswerWhatTheDocumentDescribes() {
        OrderFixtures.withRepeatingOrders(orderRepository, orderLineRepository, "WIDGET-CONTRACT-", () -> {
            String failed = traces.getAndAwaitComplete("/boom");
            String repeating = traces.getAndAwaitComplete("/orders");
            assertThat(SpanTree.issuesOfType(api.getJson("/peekaboot/api/traces/" + repeating), "REPEATED_QUERY"))
                    .as("/orders repeats its line query")
                    .isNotEmpty();
            List<String> traceIds = List.of(
                    failed,
                    repeating,
                    traces.getAndAwaitComplete("/orders/enrich"),
                    traces.getAndAwaitComplete("/orders/stats"),
                    traces.getAndAwaitComplete("/persons/abc"),
                    traces.getAndAwaitComplete("/persons"));

            for (String traceId : traceIds) {
                ApiContract.Exchange trace = contract.fetch("/peekaboot/api/traces/" + traceId);
                assertThat(trace.status()).as(traceId).isEqualTo(200);
                contract.assertMatches("/peekaboot/api/traces/{traceId}", trace);
            }
            contract.assertMatches(
                    "/peekaboot/api/traces",
                    contract.fetch("/peekaboot/api/traces?view=full&rootActionType=*&limit=10000"));
            contract.assertMatches(
                    "/peekaboot/api/traces", contract.fetch("/peekaboot/api/traces?bucket=errors&view=full"));
            contract.assertMatches("/peekaboot/api/logs", contract.fetch("/peekaboot/api/logs?traceId=" + failed));
            contract.assertMatches("/peekaboot/api/routes", contract.fetch("/peekaboot/api/routes"));
            contract.assertMatches("/peekaboot/api/errors", contract.fetch("/peekaboot/api/errors?route=/boom"));
        });
    }

    /** Above level 0 a full window carries each series' statistics in {@code stats}. */
    @Test
    void aWindowAboveLevelZeroAnswersWhatTheDocumentDescribes() {
        JsonNode window = await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> api.getJson("/peekaboot/api/insights/data?level=1&view=full"),
                        data -> data.path("count").asInt() > 0);

        contract.assertOk("/peekaboot/api/insights/data", window);
    }

    /** The test profile closes a level-1 interval every 1.5 seconds, so both events arrive within the wait. */
    @Test
    void theEventStreamSendsWhatTheDocumentDescribes() throws Exception {
        Map<String, String> events = firstEventData(Set.of("tick", "rollup"), Duration.ofSeconds(10));

        contract.assertSchemaMatches("InsightsTick", events.get("tick"));
        contract.assertSchemaMatches("InsightsRollup", events.get("rollup"));
    }

    /** The application documents its own API with springdoc, which reads Peekaboot's controllers too. */
    @Test
    void theApplicationsOwnApiDocsStillRender() {
        assertThat(api.statusOf("/v3/api-docs")).isEqualTo(HttpStatus.OK);
    }

    private List<String> parameterNames(String pathTemplate, String in) {
        return contract.document()
                .path("paths")
                .path(pathTemplate)
                .path("get")
                .path("parameters")
                .valueStream()
                .map(contract::resolved)
                .filter(parameter -> in.equals(parameter.path("in").asString()))
                .map(parameter -> parameter.path("name").asString())
                .toList();
    }

    /**
     * An object that names neither its properties nor what else it holds, or an array that does not
     * name its items, would validate anything. An open tree, such as health details, says so with
     * a schema that has no type at all.
     */
    private static boolean isHole(JsonNode schema) {
        String type = schema.path("type").toString();
        return (type.contains("\"object\"") && !schema.has("properties") && !schema.has("additionalProperties"))
                || (type.contains("\"array\"") && !schema.has("items"));
    }

    /** {@code schema} and every schema nested in it, by where each sits, so the rules reach inline objects too. */
    private static void collectSchemas(JsonNode schema, String at, Map<String, JsonNode> schemas) {
        schemas.put(at, schema);
        schema.path("properties")
                .propertyStream()
                .forEach(property -> collectSchemas(property.getValue(), at + "." + property.getKey(), schemas));
        for (String keyword : List.of("items", "additionalProperties")) {
            if (schema.path(keyword).isObject()) {
                collectSchemas(schema.path(keyword), at + "/" + keyword, schemas);
            }
        }
        for (String keyword : List.of("anyOf", "oneOf")) {
            for (int choice = 0; choice < schema.path(keyword).size(); choice++) {
                collectSchemas(schema.path(keyword).path(choice), at + "/" + keyword + "/" + choice, schemas);
            }
        }
    }

    private static void collectRefs(JsonNode node, List<String> refs) {
        node.propertyStream().forEach(property -> {
            if ("$ref".equals(property.getKey())) {
                refs.add(property.getValue().asString());
            } else {
                collectRefs(property.getValue(), refs);
            }
        });
        if (node.isArray()) {
            node.valueStream().forEach(element -> collectRefs(element, refs));
        }
    }

    /**
     * The data of the first event of each name, read off a real stream. The JDK client has no read
     * timeout, so the read runs off the test thread and the wait is bounded here; closing the body
     * ends the read.
     */
    private Map<String, String> firstEventData(Set<String> names, Duration bound) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/peekaboot/api/insights/stream"))
                            .header("Accept", "text/event-stream")
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (InputStream body = response.body()) {
                return CompletableFuture.supplyAsync(() -> readEvents(body, names))
                        .get(bound.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private static Map<String, String> readEvents(InputStream body, Set<String> names) {
        Map<String, String> data = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String event = null;
            String line;
            while (!data.keySet().containsAll(names) && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).strip();
                } else if (line.startsWith("data:") && event != null && names.contains(event)) {
                    data.putIfAbsent(event, line.substring("data:".length()));
                }
            }
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
