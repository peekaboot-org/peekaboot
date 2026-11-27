package org.peekaboot.backend.health;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.actuator.parsed.HealthResponse.ComponentDescriptor;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.domain.health.HealthReport;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;

/**
 * The actuator's health tree as the report serves it: details masked, and each component joined to
 * what it connects to.
 *
 * <p>A top-level indicator takes the connection of its source only when the source describes exactly
 * one target. A top-level composite takes none itself and gives each direct child the connection of
 * the bean of that name. Deeper levels get none.
 */
public final class HealthComponents {

    private final TreeMasker masker;
    private final List<HealthConnectionSource> sources;

    public HealthComponents(MaskingEngine maskingEngine, List<HealthConnectionSource> sources) {
        this.masker = new TreeMasker(maskingEngine);
        this.sources = List.copyOf(sources);
    }

    public Map<String, HealthReport.Component> of(@Nullable HealthResponse health) {
        Map<String, HealthReport.Component> components = new LinkedHashMap<>();
        if (health == null) {
            return components;
        }
        Map<String, Map<String, HealthConnection>> connectionsByComponent = connectionsByComponent();
        health.components().forEach((name, descriptor) -> {
            Map<String, HealthConnection> connections = connectionsByComponent.getOrDefault(name, Map.of());
            components.put(name, topLevel(descriptor, connections));
        });
        return components;
    }

    private Map<String, Map<String, HealthConnection>> connectionsByComponent() {
        Map<String, Map<String, HealthConnection>> connectionsByComponent = new LinkedHashMap<>();
        for (HealthConnectionSource source : sources) {
            connectionsByComponent
                    .computeIfAbsent(source.component(), name -> new LinkedHashMap<>())
                    .putAll(source.connections());
        }
        return connectionsByComponent;
    }

    private HealthReport.Component topLevel(ComponentDescriptor descriptor, Map<String, HealthConnection> connections) {
        if (descriptor.components().isEmpty()) {
            HealthConnection only =
                    connections.size() == 1 ? connections.values().iterator().next() : null;
            return component(descriptor, only, Map.of());
        }
        return component(descriptor, null, connections);
    }

    private HealthReport.Component component(
            ComponentDescriptor descriptor,
            @Nullable HealthConnection connection,
            Map<String, HealthConnection> childConnections) {
        Map<String, HealthReport.Component> children = new LinkedHashMap<>();
        descriptor
                .components()
                .forEach((name, child) -> children.put(name, component(child, childConnections.get(name), Map.of())));
        return new HealthReport.Component(
                descriptor.status(),
                descriptor.durationMs(),
                masker.maskMap(descriptor.details(), false),
                children,
                connection);
    }
}
