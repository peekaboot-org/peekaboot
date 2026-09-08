package org.peekaboot.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.peekaboot.backend.actuator.InsightsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The actuator data the insights mappers consume, keyed by endpoint id.
 *
 * <p>Every reading comes from an endpoint instance Peekaboot owns, constructed in
 * {@code ActuatorSourcesAutoConfiguration} and called directly. Nothing here discovers the
 * application's endpoint beans, so no {@code management.endpoint.*} setting decides what the
 * dashboard may read or whether a value arrives masked - {@code MaskingEngine} alone does.
 * Health is the one endpoint whose bean is borrowed rather than built, because it carries no
 * value-visibility gate of its own. Borrowing still leaves one lever: {@code
 * management.endpoint.health.access=none} is resolved before the bean exists, so it removes
 * the bean and the source then contributes no entry, same as an absent Flyway or logging
 * system.
 */
public final class PeekabootActuatorService {

    private static final Logger log = LoggerFactory.getLogger(PeekabootActuatorService.class);

    private final List<InsightsSource> sources;

    /** Sources whose failure was reported at WARN; their later failures log at DEBUG. */
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();

    public PeekabootActuatorService(List<InsightsSource> sources) {
        this.sources = List.copyOf(sources);
    }

    /**
     * Reads every source. One that fails is left out, so a single broken endpoint never hides
     * the others; its first failure is logged with the cause and later ones at DEBUG, so a
     * persistently broken endpoint does not warn on every dashboard refresh. One that reads
     * null contributes no entry and logs nothing.
     */
    public Map<String, Object> getInsightsData() {
        Map<String, Object> results = new LinkedHashMap<>();
        for (InsightsSource source : sources) {
            read(source, results);
        }
        return results;
    }

    private void read(InsightsSource source, Map<String, Object> results) {
        try {
            Object data = source.read().get();
            if (data != null) {
                results.put(source.id(), data);
            }
        } catch (Exception e) {
            if (reportedFailures.add(source.id())) {
                log.warn("Actuator endpoint '{}' failed", source.id(), e);
            } else {
                log.debug("Actuator endpoint '{}' failed again: {}", source.id(), e.toString());
            }
        }
    }
}
