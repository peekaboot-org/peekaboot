package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.LiquibaseResponse;
import org.peekaboot.backend.domain.liquibase.ChangeSetExecType;
import org.peekaboot.backend.domain.liquibase.ChangeSetInfo;
import org.peekaboot.backend.domain.liquibase.LiquibaseInfo;

class LiquibaseMapperTest {

    private final LiquibaseMapper mapper = new LiquibaseMapper();

    private static LiquibaseResponse liquibase(LiquibaseResponse.ChangeSet... changeSets) {
        return new LiquibaseResponse(Map.of(
                "application",
                new LiquibaseResponse.LiquibaseContext(
                        Map.of("liquibase", new LiquibaseResponse.LiquibaseBean(List.of(changeSets))), null)));
    }

    private static LiquibaseResponse.ChangeSet changeSet(String id, Integer orderExecuted) {
        return new LiquibaseResponse.ChangeSet(null, null, null, null, "EXECUTED", id, orderExecuted);
    }

    @Test
    void extractsChangeSets() {
        LiquibaseResponse liquibaseData = liquibase(new LiquibaseResponse.ChangeSet(
                "peekaboot",
                "db/changelog/inventory/db.changelog-master.yaml",
                Instant.parse("2026-10-02T09:00:00Z"),
                "createTable tableName=product",
                "EXECUTED",
                "create-product",
                1));

        LiquibaseInfo result = mapper.map(liquibaseData);

        assertThat(result.changeSets())
                .extracting(
                        ChangeSetInfo::orderExecuted,
                        ChangeSetInfo::id,
                        ChangeSetInfo::author,
                        ChangeSetInfo::changeLog,
                        ChangeSetInfo::description,
                        ChangeSetInfo::dateExecuted,
                        ChangeSetInfo::execType)
                .containsExactly(tuple(
                        1,
                        "create-product",
                        "peekaboot",
                        "db/changelog/inventory/db.changelog-master.yaml",
                        "createTable tableName=product",
                        Instant.parse("2026-10-02T09:00:00Z"),
                        ChangeSetExecType.EXECUTED));
    }

    // Two beans each count from 1, so their change sets interleave and a tie keeps arrival order.
    @Test
    void flattensEveryBeanOfEveryContextInExecutionOrder() {
        Map<String, LiquibaseResponse.LiquibaseBean> beans = new LinkedHashMap<>();
        beans.put(
                "ordersLiquibase",
                new LiquibaseResponse.LiquibaseBean(List.of(changeSet("orders-2", 2), changeSet("orders-1", 1))));
        beans.put(
                "inventoryLiquibase",
                new LiquibaseResponse.LiquibaseBean(List.of(changeSet("inventory-1", 1), changeSet("inventory-3", 3))));
        Map<String, LiquibaseResponse.LiquibaseContext> contexts = new LinkedHashMap<>();
        contexts.put("application", new LiquibaseResponse.LiquibaseContext(beans, null));
        contexts.put(
                "parent",
                new LiquibaseResponse.LiquibaseContext(
                        Map.of(
                                "parentLiquibase",
                                new LiquibaseResponse.LiquibaseBean(List.of(changeSet("parent-4", 4)))),
                        null));

        LiquibaseInfo result = mapper.map(new LiquibaseResponse(contexts));

        assertThat(result.changeSets())
                .extracting(ChangeSetInfo::id)
                .containsExactly("orders-1", "inventory-1", "orders-2", "inventory-3", "parent-4");
    }

    @Test
    void changeSetsWithoutAnOrderGoLast() {
        LiquibaseInfo result =
                mapper.map(liquibase(changeSet("unordered", null), changeSet("second", 2), changeSet("first", 1)));

        assertThat(result.changeSets()).extracting(ChangeSetInfo::id).containsExactly("first", "second", "unordered");
    }

    @Test
    void mapsANullResponseToNoChangeSets() {
        assertThat(mapper.map(null).changeSets()).isEmpty();
    }

    @Test
    void absentBeansAndChangeSetsReadAsNoChangeSets() {
        LiquibaseResponse liquibaseData = new LiquibaseResponse(Map.of(
                "empty", new LiquibaseResponse.LiquibaseContext(null, null),
                "application",
                        new LiquibaseResponse.LiquibaseContext(
                                Map.of("liquibase", new LiquibaseResponse.LiquibaseBean(null)), null)));

        assertThat(mapper.map(liquibaseData).changeSets()).isEmpty();
    }
}
