package org.peekaboot.backend.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.actuator.parsed.HealthResponse.ComponentDescriptor;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.peekaboot.backend.masking.MaskingEngine;

/** How the actuator's health tree becomes the report's components. */
class HealthComponentsTest {

    private static final DataSourceConnection SHOP =
            new DataSourceConnection("dataSource", "POSTGRESQL", List.of(), "shop", null);
    private static final DataSourceConnection INVENTORY =
            new DataSourceConnection("inventory", "MYSQL", List.of(), "stock", null);
    private static final RedisConnection REDIS = new RedisConnection(List.of(), 0);

    @Test
    void mirrorsTheActuatorsNestingWithEveryKeyOnEveryNode() {
        HealthResponse health = response(Map.of(
                "db",
                composite("UP", Map.of("dataSource", indicator("UP"), "inventory", indicator("UP"))),
                "diskSpace",
                indicator("UP")));

        Map<String, HealthReport.Component> components = components().of(health);

        assertThat(components).containsOnlyKeys("db", "diskSpace");
        assertThat(components.get("db").status()).isEqualTo("UP");
        assertThat(components.get("db").details()).isEmpty();
        assertThat(components.get("db").components()).containsOnlyKeys("dataSource", "inventory");
        assertThat(components.get("diskSpace").components()).isEmpty();
    }

    @Test
    void carriesEachComponentsDurationAtEveryLevel() {
        HealthResponse health = response(Map.of(
                "db",
                new ComponentDescriptor(
                        "UP",
                        40L,
                        Map.of(),
                        Map.of("primary", new ComponentDescriptor("UP", 40L, Map.of(), Map.of())))));

        HealthReport.Component db = components().of(health).get("db");

        assertThat(db.durationMs()).isEqualTo(40L);
        assertThat(db.components().get("primary").durationMs()).isEqualTo(40L);
    }

    @Test
    void anUntimedComponentHasNoDuration() {
        HealthResponse health = response(Map.of("ping", indicator("UP")));

        assertThat(components().of(health).get("ping").durationMs()).isNull();
    }

    @Test
    void keepsTheActuatorsOrder() {
        Map<String, ComponentDescriptor> ordered = new LinkedHashMap<>();
        ordered.put("zeta", indicator("UP"));
        ordered.put("alpha", indicator("UP"));

        assertThat(components().of(response(ordered)).keySet()).containsExactly("zeta", "alpha");
    }

    @Test
    void aSingleIndicatorGetsItsSourcesOnlyConnection() {
        HealthResponse health = response(Map.of("db", indicator("UP")));

        Map<String, HealthReport.Component> components =
                components(source("db", Map.of("dataSource", SHOP))).of(health);

        assertThat(components.get("db").connection()).isEqualTo(SHOP);
    }

    @Test
    void eachChildOfACompositeGetsTheConnectionOfItsBean() {
        HealthResponse health = response(
                Map.of("db", composite("UP", Map.of("dataSource", indicator("UP"), "inventory", indicator("UP")))));

        HealthReport.Component db = components(source("db", Map.of("dataSource", SHOP, "inventory", INVENTORY)))
                .of(health)
                .get("db");

        assertThat(db.connection()).isNull();
        assertThat(db.components().get("dataSource").connection()).isEqualTo(SHOP);
        assertThat(db.components().get("inventory").connection()).isEqualTo(INVENTORY);
    }

    @Test
    void aChildThatNamesNoBeanHasNoConnection() {
        HealthResponse health = response(Map.of(
                "redis", composite("UP", Map.of("cacheFactory", indicator("UP"), "sessionFactory", indicator("UP")))));

        HealthReport.Component redis =
                components(source("redis", Map.of("redis", REDIS))).of(health).get("redis");

        assertThat(redis.connection()).isNull();
        assertThat(redis.components().get("cacheFactory").connection()).isNull();
        assertThat(redis.components().get("sessionFactory").connection()).isNull();
    }

    @Test
    void anIndicatorWithSeveralCandidatesHasNoConnection() {
        HealthResponse health = response(Map.of("db", indicator("UP")));

        Map<String, HealthReport.Component> components = components(
                        source("db", Map.of("dataSource", SHOP, "inventory", INVENTORY)))
                .of(health);

        assertThat(components.get("db").connection()).isNull();
    }

    @Test
    void sourcesOfOneComponentAreMergedBeforeTheyAreJoined() {
        HealthResponse health = response(Map.of("db", indicator("UP")));

        Map<String, HealthReport.Component> components = components(
                        source("db", Map.of("dataSource", SHOP)), source("db", Map.of("inventory", INVENTORY)))
                .of(health);

        assertThat(components.get("db").connection()).isNull();
    }

    @Test
    void aComponentNoSourceDescribesHasNoConnection() {
        HealthResponse health = response(Map.of("diskSpace", indicator("UP"), "db", indicator("UP")));

        Map<String, HealthReport.Component> components =
                components(source("db", Map.of("dataSource", SHOP))).of(health);

        assertThat(components.get("diskSpace").connection()).isNull();
    }

    @Test
    void deeperLevelsGetNoConnection() {
        HealthResponse health = response(Map.of(
                "db", composite("UP", Map.of("dataSource", composite("UP", Map.of("dataSource", indicator("UP")))))));

        HealthReport.Component nested = components(source("db", Map.of("dataSource", SHOP)))
                .of(health)
                .get("db")
                .components()
                .get("dataSource");

        assertThat(nested.connection()).isEqualTo(SHOP);
        assertThat(nested.components().get("dataSource").connection()).isNull();
    }

    @Test
    void readsTheConnectionsAnewForEachReport() {
        Map<String, HealthConnection> connections = new LinkedHashMap<>();
        HealthComponents components = components(source("db", connections));
        HealthResponse health = response(Map.of("db", indicator("UP")));

        assertThat(components.of(health).get("db").connection()).isNull();

        connections.put("dataSource", SHOP);

        assertThat(components.of(health).get("db").connection()).isEqualTo(SHOP);
    }

    @Test
    void masksDetailsAsTheDashboardDoes() {
        Map<String, Object> details =
                Map.of("error", "java.net.ConnectException: redis://cache:s3cret@cache.internal:6379", "password", "x");
        HealthResponse health = response(Map.of("redis", new ComponentDescriptor("DOWN", null, details, Map.of())));

        Map<String, Object> masked = components().of(health).get("redis").details();

        assertThat(masked)
                .containsEntry("error", "java.net.ConnectException: redis://******@cache.internal:6379")
                .containsEntry("password", "******");
    }

    @Test
    void masksTheDetailsOfACompositesChildren() {
        Map<String, Object> details = Map.of(
                "error", "java.sql.SQLException: Connection to jdbc:postgresql://shop:s3cret@db.internal/shop refused");
        HealthResponse health = response(Map.of(
                "db",
                composite(
                        "DOWN",
                        Map.of(
                                "dataSource",
                                new ComponentDescriptor("DOWN", null, details, Map.of()),
                                "inventory",
                                indicator("UP")))));

        Map<String, Object> masked =
                components().of(health).get("db").components().get("dataSource").details();

        assertThat(masked)
                .containsExactly(Map.entry(
                        "error",
                        "java.sql.SQLException: Connection to jdbc:postgresql://******@db.internal/shop refused"));
    }

    @Test
    void withoutHealthThereAreNoComponents() {
        assertThat(components().of(null)).isEmpty();
    }

    private static HealthComponents components(HealthConnectionSource... sources) {
        return new HealthComponents(new MaskingEngine(), List.of(sources));
    }

    private static HealthConnectionSource source(String component, Map<String, HealthConnection> connections) {
        return new HealthConnectionSource() {
            @Override
            public String component() {
                return component;
            }

            @Override
            public Map<String, HealthConnection> connections() {
                return connections;
            }
        };
    }

    private static HealthResponse response(Map<String, ComponentDescriptor> components) {
        return new HealthResponse("UP", components);
    }

    private static ComponentDescriptor indicator(String status) {
        return new ComponentDescriptor(status, null, Map.of(), Map.of());
    }

    private static ComponentDescriptor composite(String status, Map<String, ComponentDescriptor> children) {
        return new ComponentDescriptor(status, null, Map.of(), children);
    }
}
