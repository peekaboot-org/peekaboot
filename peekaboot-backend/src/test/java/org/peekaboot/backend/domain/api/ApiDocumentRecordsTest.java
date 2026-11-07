package org.peekaboot.backend.domain.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.insights.AggregateStats;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import tools.jackson.databind.jsonFormatVisitors.JsonObjectFormatVisitor;

/**
 * Every schema of the OpenAPI document that is named after a backend type lists what Jackson writes
 * for that type, so the document holds to the code for shapes no running application produces.
 */
class ApiDocumentRecordsTest {

    private static final String BACKEND_PACKAGE = "org.peekaboot.backend";

    /** Shapes the backend writes from maps, which no type describes. */
    private static final Set<String> SCHEMAS_WITHOUT_A_TYPE =
            Set.of("InsightsTick", "InsightsRollup", "RouteStats.IssueCounts");

    /** Values the backend holds as strings, so no enum states what the document allows. */
    private static final Set<String> VALUES_WITHOUT_AN_ENUM =
            Set.of("LifecycleEventsResponse.Event.type", "LifecycleRunsResponse.Run.changed/items");

    /** Where the document lists the statistic names a series carries its aggregates under. */
    private static final String STAT_NAMES_AT = "InsightsDataResponse.SeriesData.stats/propertyNames";

    private static final JsonNode DOCUMENT = ApiDocument.bundled().forServer("").content();

    private static final JsonNode SCHEMAS = DOCUMENT.path("components").path("schemas");

    private static final Map<String, List<String>> BACKEND_TYPES = backendTypesBySchemaName();

    static Stream<String> schemasNamedAfterAType() {
        return SCHEMAS.propertyNames().stream().filter(name -> !SCHEMAS_WITHOUT_A_TYPE.contains(name));
    }

    @Test
    void theSchemasWithoutATypeAreOnesTheBackendHasNoTypeFor() {
        assertThat(SCHEMAS_WITHOUT_A_TYPE).allSatisfy(name -> {
            assertThat(SCHEMAS.has(name)).as("the document describes %s", name).isTrue();
            assertThat(BACKEND_TYPES).as("the backend types").doesNotContainKey(name);
        });
    }

    @Test
    void theValuesWithoutAnEnumAreStringsTheDocumentListsValuesFor() {
        assertThat(VALUES_WITHOUT_AN_ENUM).allSatisfy(at -> {
            assertThat(at).as("held to AggregateStats.STAT_NAMES").isNotEqualTo(STAT_NAMES_AT);
            Location location = locate(at);
            assertThat(location.schema().path("enum").valueStream().filter(value -> !value.isNull()))
                    .as("the values the document lists at %s", at)
                    .isNotEmpty()
                    .allMatch(JsonNode::isString);
            assertThat(location.type())
                    .as("the type at %s", at)
                    .isNotNull()
                    .extracting(JavaType::getRawClass)
                    .isEqualTo(String.class);
        });
    }

    @Test
    void theSeriesStatsAreKeyedByTheStatisticNames() {
        assertThat(allowedValues(locate(STAT_NAMES_AT).schema()))
                .as("the values of %s", STAT_NAMES_AT)
                .containsExactlyInAnyOrderElementsOf(AggregateStats.STAT_NAMES);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemasNamedAfterAType")
    void aSchemaHoldsToTheTypeItIsNamedAfter(String name) {
        assertThat(BACKEND_TYPES.get(name))
                .as("the backend types named %s", name)
                .hasSize(1);
        Class<?> type = ClassUtils.resolveClassName(BACKEND_TYPES.get(name).getFirst(), null);
        JsonNode schema = SCHEMAS.path(name);

        if (type.isEnum()) {
            assertThat(allowedValues(schema)).as(name).containsExactlyInAnyOrderElementsOf(wireValues(type));
        } else if (type.isSealed()) {
            assertThat(schema.path("oneOf")
                            .valueStream()
                            .map(choice -> refName(choice).orElse("")))
                    .as(name)
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(type.getPermittedSubclasses())
                            .map(ClassUtils::getShortName)
                            .toList());
            assertTheBranchesShareEveryValue(type);
        } else if (type == OpenApiDocument.class) {
            // Jackson writes the document it holds, through @JsonValue
            assertThat(schema.path("properties").propertyNames()).containsExactlyElementsOf(DOCUMENT.propertyNames());
        } else {
            Map<String, JavaType> written = writtenProperties(type);
            assertThat(schema.path("properties").propertyNames())
                    .as("the properties of %s", name)
                    .containsExactlyElementsOf(written.keySet());
            boolean branch = Arrays.stream(type.getInterfaces()).anyMatch(Class::isSealed);
            written.forEach((property, propertyType) -> assertValuesHold(
                    name + "." + property, schema.path("properties").path(property), propertyType, branch));
        }
    }

    /**
     * Each branch of a sealed type lists the share of an enum's values it carries, such as the issue
     * types a {@code SpanIssue} can have, so together the branches list every value exactly once.
     */
    private static void assertTheBranchesShareEveryValue(Class<?> sealedType) {
        Map<String, List<String>> shared = new LinkedHashMap<>();
        Map<String, Class<?>> enums = new LinkedHashMap<>();
        for (Class<?> branch : sealedType.getPermittedSubclasses()) {
            JsonNode properties = SCHEMAS.path(ClassUtils.getShortName(branch)).path("properties");
            writtenProperties(branch).forEach((property, propertyType) -> {
                if (propertyType.isEnumType()
                        && refName(properties.path(property)).isEmpty()) {
                    shared.computeIfAbsent(property, values -> new ArrayList<>())
                            .addAll(allowedValues(properties.path(property)));
                    enums.put(property, propertyType.getRawClass());
                }
            });
        }
        shared.forEach((property, values) -> assertThat(values)
                .as("the values of %s across the branches of %s", property, sealedType.getSimpleName())
                .containsExactlyInAnyOrderElementsOf(wireValues(enums.get(property))));
    }

    /**
     * Where the Java type is an enum, the document allows its values, or the share of them a branch of
     * a sealed type carries; where the document lists values, an enum holds them.
     */
    private static void assertValuesHold(String at, JsonNode schema, JavaType type, boolean branch) {
        if (type.isEnumType()) {
            Optional<String> ref = refName(schema);
            if (ref.isPresent()) {
                assertThat(ref.get())
                        .as("the schema %s refers to", at)
                        .isEqualTo(ClassUtils.getShortName(type.getRawClass()));
            } else if (branch) {
                assertThat(allowedValues(schema)).as("the values of %s", at).isSubsetOf(wireValues(type.getRawClass()));
            } else {
                assertThat(allowedValues(schema))
                        .as("the values of %s", at)
                        .containsExactlyInAnyOrderElementsOf(wireValues(type.getRawClass()));
            }
        } else if (schema.has("enum") && !at.equals(STAT_NAMES_AT)) {
            assertThat(VALUES_WITHOUT_AN_ENUM)
                    .as("%s lists values, and its type %s is no enum", at, type)
                    .contains(at);
        }
        if ((type.isCollectionLikeType() || type.isArrayType()) && schema.has("items")) {
            assertValuesHold(at + "/items", schema.path("items"), type.getContentType(), branch);
        }
        if (type.isMapLikeType()) {
            if (schema.has("propertyNames")) {
                assertValuesHold(at + "/propertyNames", schema.path("propertyNames"), type.getKeyType(), branch);
            }
            if (schema.path("additionalProperties").isObject()) {
                assertValuesHold(
                        at + "/additionalProperties",
                        schema.path("additionalProperties"),
                        type.getContentType(),
                        branch);
            }
        }
    }

    /** The schema and the Java type at {@code at}, a path as {@link #assertValuesHold} names it. */
    private static Location locate(String at) {
        List<String> steps = List.of(at.split("/"));
        int dot = steps.getFirst().lastIndexOf('.');
        String name = steps.getFirst().substring(0, dot);
        String property = steps.getFirst().substring(dot + 1);
        JsonNode schema = SCHEMAS.path(name).path("properties").path(property);
        JavaType type = BACKEND_TYPES.containsKey(name)
                ? writtenProperties(ClassUtils.resolveClassName(
                                BACKEND_TYPES.get(name).getFirst(), null))
                        .get(property)
                : null;
        for (String step : steps.subList(1, steps.size())) {
            schema = schema.path(step);
            if (type != null) {
                type = step.equals("propertyNames") ? type.getKeyType() : type.getContentType();
            }
        }
        return new Location(schema, type);
    }

    private record Location(JsonNode schema, @Nullable JavaType type) {}

    /** The properties Jackson writes for {@code type}, in its order, with their types. */
    private static Map<String, JavaType> writtenProperties(Class<?> type) {
        Map<String, JavaType> properties = new LinkedHashMap<>();
        PeekabootJson.MAPPER.acceptJsonFormatVisitor(type, new JsonFormatVisitorWrapper.Base() {
            @Override
            public JsonObjectFormatVisitor expectObjectFormat(JavaType objectType) {
                return new JsonObjectFormatVisitor.Base(getContext()) {
                    @Override
                    public void property(BeanProperty property) {
                        properties.put(property.getName(), property.getType());
                    }

                    @Override
                    public void optionalProperty(BeanProperty property) {
                        property(property);
                    }
                };
            }
        });
        return properties;
    }

    /** The values {@code schema} allows, null aside, since whether a value may be absent is a separate fact. */
    private static List<String> allowedValues(JsonNode schema) {
        if (schema.has("const")) {
            return List.of(schema.path("const").asString());
        }
        return schema.path("enum")
                .valueStream()
                .filter(value -> !value.isNull())
                .map(JsonNode::asString)
                .toList();
    }

    private static List<String> wireValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> PeekabootJson.MAPPER.valueToTree(constant).asString())
                .toList();
    }

    /** The schema {@code schema} refers to, directly or as the one choice besides null. */
    private static Optional<String> refName(JsonNode schema) {
        return Stream.concat(Stream.of(schema), schema.path("anyOf").valueStream())
                .map(candidate -> candidate.path("$ref").asString(""))
                .filter(ref -> !ref.isEmpty())
                .map(ref -> ref.substring(ref.lastIndexOf('/') + 1))
                .findFirst();
    }

    /** Every class under the backend package by its name inside its package, nested names joined with dots, read without loading any. */
    private static Map<String, List<String>> backendTypesBySchemaName() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter((reader, factory) -> true);
        return scanner.findCandidateComponents(BACKEND_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .collect(Collectors.groupingBy(ClassUtils::getShortName));
    }
}
