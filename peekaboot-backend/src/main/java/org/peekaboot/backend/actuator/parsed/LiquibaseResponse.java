package org.peekaboot.backend.actuator.parsed;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Absent collections bind as empty (see {@link ActuatorResponseParser}). */
public record LiquibaseResponse(Map<String, LiquibaseContext> contexts) {

    public LiquibaseResponse {
        contexts = Absent.orEmpty(contexts);
    }

    public record LiquibaseContext(Map<String, LiquibaseBean> liquibaseBeans, String parentId) {
        public LiquibaseContext {
            liquibaseBeans = Absent.orEmpty(liquibaseBeans);
        }
    }

    public record LiquibaseBean(List<ChangeSet> changeSets) {
        public LiquibaseBean {
            changeSets = Absent.orEmpty(changeSets);
        }
    }

    public record ChangeSet(
            String author,
            String changeLog,
            Instant dateExecuted,
            String description,
            String execType,
            String id,
            Integer orderExecuted) {}
}
