package org.peekaboot.backend.actuator.parsed;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Absent collections bind as empty (see {@link ActuatorResponseParser}). */
public record FlywayResponse(Map<String, FlywayContext> contexts) {

    public FlywayResponse {
        contexts = Absent.orEmpty(contexts);
    }

    public record FlywayContext(Map<String, FlywayBean> flywayBeans, String parentId) {
        public FlywayContext {
            flywayBeans = Absent.orEmpty(flywayBeans);
        }
    }

    public record FlywayBean(List<Migration> migrations) {
        public FlywayBean {
            migrations = Absent.orEmpty(migrations);
        }
    }

    public record Migration(
            String description,
            Integer executionTime,
            Instant installedOn,
            String script,
            String state,
            String type,
            String version) {}
}
