package org.peekaboot.autoconfigure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.peekaboot.autoconfigure.HealthCheckRuns.Result;
import org.peekaboot.autoconfigure.HealthCheckRuns.Run;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.actuator.parsed.HealthResponse.ComponentDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributors;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reads the health contributor tree as {@code HealthEndpointSupport} does for the primary group
 * with components and details shown, but starts every check before it waits for any, and waits
 * for all of them against one deadline.
 */
final class HealthWalk {

    private final HealthCheckRuns runs;

    HealthWalk(HealthCheckRuns runs) {
        this.runs = runs;
    }

    HealthResponse read(HealthContributors contributors, HealthEndpointGroup group) {
        List<Node> tree = start(contributors, "", group);
        long deadline = runs.deadline();
        Map<String, Component> components = await(tree, deadline, group);
        if (components.isEmpty()) {
            return new HealthResponse("UP", Map.of());
        }
        return new HealthResponse(aggregate(group, components).status().getCode(), descriptors(components));
    }

    private List<Node> start(HealthContributors contributors, String parentPath, HealthEndpointGroup group) {
        List<Node> nodes = new ArrayList<>();
        for (HealthContributors.Entry entry : contributors) {
            String path = parentPath.isEmpty() ? entry.name() : parentPath + "/" + entry.name();
            if (entry.contributor() instanceof HealthContributors composite) {
                nodes.add(new Composite(entry.name(), start(composite, path, group)));
            } else if (group.isMember(path)) {
                nodes.add(new Leaf(entry.name(), runs.start(path, (HealthIndicator) entry.contributor())));
            }
        }
        return nodes;
    }

    private Map<String, Component> await(List<Node> nodes, long deadline, HealthEndpointGroup group) {
        Map<String, Component> components = new LinkedHashMap<>();
        for (Node node : nodes) {
            Component component =
                    switch (node) {
                        case Leaf leaf -> leaf(runs.await(leaf.run(), deadline));
                        case Composite composite -> composite(composite, deadline, group);
                    };
            if (component != null) {
                components.put(node.name(), component);
            }
        }
        return components;
    }

    private static @Nullable Component leaf(Result result) {
        Health health = result.health();
        if (health == null) {
            return null;
        }
        return new Component(
                health.getStatus(),
                result.durationMs(),
                new ComponentDescriptor(
                        health.getStatus().getCode(), result.durationMs(), health.getDetails(), Map.of()));
    }

    private @Nullable Component composite(Composite composite, long deadline, HealthEndpointGroup group) {
        Map<String, Component> children = await(composite.children(), deadline, group);
        return children.isEmpty() ? null : aggregate(group, children);
    }

    private static Component aggregate(HealthEndpointGroup group, Map<String, Component> children) {
        Set<Status> statuses = children.values().stream().map(Component::status).collect(Collectors.toSet());
        Status aggregate = group.getStatusAggregator().getAggregateStatus(statuses);
        Status status = aggregate != null ? aggregate : Status.UNKNOWN;
        long slowest = children.values().stream()
                .mapToLong(Component::durationMs)
                .max()
                .orElse(0);
        return new Component(
                status, slowest, new ComponentDescriptor(status.getCode(), slowest, Map.of(), descriptors(children)));
    }

    private static Map<String, ComponentDescriptor> descriptors(Map<String, Component> components) {
        Map<String, ComponentDescriptor> descriptors = new LinkedHashMap<>();
        components.forEach((name, component) -> descriptors.put(name, component.descriptor()));
        return descriptors;
    }

    private sealed interface Node permits Leaf, Composite {

        String name();
    }

    private record Leaf(String name, Run run) implements Node {}

    private record Composite(String name, List<Node> children) implements Node {}

    /** Keeps the {@link Status} itself, since aggregators take statuses, not codes. */
    private record Component(Status status, long durationMs, ComponentDescriptor descriptor) {}
}
