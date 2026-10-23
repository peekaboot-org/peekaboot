package org.peekaboot.backend.mapper.actuator;

import java.util.Comparator;
import java.util.List;
import org.peekaboot.backend.actuator.parsed.LiquibaseResponse;
import org.peekaboot.backend.domain.liquibase.ChangeSetExecType;
import org.peekaboot.backend.domain.liquibase.ChangeSetInfo;
import org.peekaboot.backend.domain.liquibase.LiquibaseInfo;

public class LiquibaseMapper {

    private static final Comparator<ChangeSetInfo> EXECUTION_ORDER =
            Comparator.comparing(ChangeSetInfo::orderExecuted, Comparator.nullsLast(Comparator.naturalOrder()));

    public LiquibaseInfo map(LiquibaseResponse liquibaseData) {
        if (liquibaseData == null) {
            return new LiquibaseInfo(List.of());
        }

        // The endpoint answers in the history table's own order; the tab promises orderExecuted.
        return new LiquibaseInfo(liquibaseData.contexts().values().stream()
                .flatMap(context -> context.liquibaseBeans().values().stream())
                .flatMap(bean -> bean.changeSets().stream())
                .map(this::mapChangeSet)
                .sorted(EXECUTION_ORDER)
                .toList());
    }

    private ChangeSetInfo mapChangeSet(LiquibaseResponse.ChangeSet changeSet) {
        return new ChangeSetInfo(
                changeSet.orderExecuted(),
                changeSet.id(),
                changeSet.author(),
                changeSet.changeLog(),
                changeSet.description(),
                changeSet.dateExecuted(),
                ChangeSetExecType.fromString(changeSet.execType()));
    }
}
