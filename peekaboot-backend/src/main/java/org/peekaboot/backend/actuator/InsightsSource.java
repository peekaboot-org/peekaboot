package org.peekaboot.backend.actuator;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * One actuator reading the insights mappers consume, identified the way the dashboard's
 * payload keys it. {@code read} is invoked per dashboard refresh and may return null,
 * which contributes no entry - that is how an endpoint whose backing bean is absent
 * (health without the actuator's health endpoint, flyway or liquibase without its
 * migration bean) reports that it has nothing rather than failing.
 *
 * @param id one of the section ids declared on
 *     {@link org.peekaboot.backend.actuator.parsed.ActuatorParsedData}
 */
public record InsightsSource(String id, Supplier<@Nullable Object> read) {}
