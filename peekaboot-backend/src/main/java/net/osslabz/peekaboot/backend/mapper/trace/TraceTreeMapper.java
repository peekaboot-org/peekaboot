package net.osslabz.peekaboot.backend.mapper.trace;

import io.micrometer.tracing.Span;
import net.osslabz.peekaboot.backend.domain.trace.SpanIssue;
import net.osslabz.peekaboot.backend.domain.trace.SpanNode;
import net.osslabz.peekaboot.backend.domain.trace.TraceMetrics;
import net.osslabz.peekaboot.backend.domain.trace.TraceStatus;
import net.osslabz.peekaboot.backend.domain.trace.TraceTree;
import net.osslabz.peekaboot.tracing.store.SpanData;
import net.osslabz.peekaboot.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class TraceTreeMapper {

    public TraceTree map(TraceData traceData) {
        if (traceData == null || traceData.spans() == null || traceData.spans().isEmpty()) {
            return new TraceTree(
                    traceData != null ? traceData.traceId() : null,
                    0L, 0L,
                    TraceStatus.OK,
                    null, null,
                    new TraceMetrics(0, 0, 0L, 0, 0),
                    Map.of()
            );
        }

        List<SpanData> spans = traceData.spans();

        // Build lookup maps
        Map<String, SpanData> spanById = spans.stream()
                .collect(Collectors.toMap(SpanData::spanId, s -> s));
        Map<String, List<SpanData>> childrenByParentId = spans.stream()
                .filter(s -> s.parentId() != null)
                .collect(Collectors.groupingBy(SpanData::parentId));

        // Find root span(s) - either null parentId or parent not in this trace
        SpanData rootSpanData = findRootSpan(spans, spanById);

        // Calculate metrics
        TraceMetrics metrics = calculateMetrics(spans);

        // Determine trace status
        TraceStatus status = determineStatus(spans);

        // Build tree with mutable nodes first (for attribute hoisting)
        MutableSpanNode mutableRoot = buildMutableTree(rootSpanData, childrenByParentId);

        // Perform attribute hoisting
        Map<String, Object> inheritedAttributes = hoistAttributes(mutableRoot);

        // Convert to immutable SpanNode
        SpanNode rootSpan = convertToImmutable(mutableRoot);

        long startTimeMs = traceData.startTime() != null ? traceData.startTime().toEpochMilli() : 0L;
        long durationMs = traceData.duration() != null ? traceData.duration().toMillis() : 0L;
        String rootOperation = rootSpanData != null ? rootSpanData.name() : null;

        return new TraceTree(
                traceData.traceId(),
                startTimeMs,
                durationMs,
                status,
                rootOperation,
                rootSpan,
                metrics,
                inheritedAttributes
        );
    }

    private SpanData findRootSpan(List<SpanData> spans, Map<String, SpanData> spanById) {
        // Find span with null parentId or parent not in this trace
        for (SpanData span : spans) {
            if (span.parentId() == null || !spanById.containsKey(span.parentId())) {
                return span;
            }
        }
        // Fallback: return first span
        return spans.isEmpty() ? null : spans.get(0);
    }

    private MutableSpanNode buildMutableTree(SpanData spanData, Map<String, List<SpanData>> childrenByParentId) {
        if (spanData == null) {
            return null;
        }

        List<SpanData> childSpans = childrenByParentId.getOrDefault(spanData.spanId(), List.of());
        List<MutableSpanNode> children = childSpans.stream()
                .sorted(Comparator.comparing(SpanData::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(child -> buildMutableTree(child, childrenByParentId))
                .toList();

        String status = spanData.hasError() ? "ERROR" : "OK";
        String kind = spanData.kind() != null ? spanData.kind().name() : null;
        long startTimeMs = spanData.startTime() != null ? spanData.startTime().toEpochMilli() : 0L;
        long durationMs = spanData.duration() != null ? spanData.duration().toMillis() : 0L;

        // Copy tags as attributes (mutable for hoisting)
        Map<String, Object> attributes = new HashMap<>();
        if (spanData.tags() != null) {
            attributes.putAll(spanData.tags());
        }

        return new MutableSpanNode(
                spanData.spanId(),
                spanData.name(),
                kind,
                startTimeMs,
                durationMs,
                status,
                new ArrayList<>(children),
                attributes,
                new ArrayList<>()
        );
    }

    private Map<String, Object> hoistAttributes(MutableSpanNode root) {
        if (root == null) {
            return Map.of();
        }

        // Count total spans in tree
        int totalSpans = countSpans(root);

        // Collect attributes with their values and occurrence counts
        Map<String, Map<Object, Integer>> attributeValueCounts = new HashMap<>();
        collectAttributeValueCounts(root, attributeValueCounts);

        // Find trace-wide common attributes: same value on EVERY span
        Map<String, Object> inheritedAttributes = new HashMap<>();
        for (Map.Entry<String, Map<Object, Integer>> entry : attributeValueCounts.entrySet()) {
            Map<Object, Integer> valueCounts = entry.getValue();
            // Only one distinct value AND that value appears on every span
            if (valueCounts.size() == 1) {
                Map.Entry<Object, Integer> singleValue = valueCounts.entrySet().iterator().next();
                if (singleValue.getValue() == totalSpans) {
                    inheritedAttributes.put(entry.getKey(), singleValue.getKey());
                }
            }
        }

        // Remove trace-wide attributes from all nodes before hoisting
        removeAttributesFromTree(root, inheritedAttributes.keySet());

        // Perform bottom-up hoisting within subtrees
        hoistAttributesRecursive(root);

        return inheritedAttributes;
    }

    private int countSpans(MutableSpanNode node) {
        int count = 1;
        for (MutableSpanNode child : node.children) {
            count += countSpans(child);
        }
        return count;
    }

    private void collectAttributeValueCounts(MutableSpanNode node, Map<String, Map<Object, Integer>> counts) {
        for (Map.Entry<String, Object> attr : node.attributes.entrySet()) {
            counts.computeIfAbsent(attr.getKey(), k -> new HashMap<>())
                    .merge(attr.getValue(), 1, Integer::sum);
        }
        for (MutableSpanNode child : node.children) {
            collectAttributeValueCounts(child, counts);
        }
    }

    private void removeAttributesFromTree(MutableSpanNode node, Set<String> keysToRemove) {
        node.attributes.keySet().removeAll(keysToRemove);
        for (MutableSpanNode child : node.children) {
            removeAttributesFromTree(child, keysToRemove);
        }
    }

    private void hoistAttributesRecursive(MutableSpanNode node) {
        if (node.children.isEmpty()) {
            return;
        }

        // First, recursively hoist in children
        for (MutableSpanNode child : node.children) {
            hoistAttributesRecursive(child);
        }

        // Find attributes common to ALL children
        Map<String, Object> commonChildAttributes = findCommonAttributes(node.children);

        // Hoist common attributes to parent
        for (Map.Entry<String, Object> common : commonChildAttributes.entrySet()) {
            String key = common.getKey();
            Object value = common.getValue();

            // Add to parent if not already there with different value
            if (!node.attributes.containsKey(key) || node.attributes.get(key).equals(value)) {
                node.attributes.put(key, value);
                // Remove from all children
                for (MutableSpanNode child : node.children) {
                    child.attributes.remove(key);
                }
            }
        }
    }

    private Map<String, Object> findCommonAttributes(List<MutableSpanNode> children) {
        if (children.isEmpty()) {
            return Map.of();
        }

        // Start with all attributes from first child
        Map<String, Object> common = new HashMap<>(children.get(0).attributes);

        // Intersect with each subsequent child
        for (int i = 1; i < children.size(); i++) {
            Map<String, Object> childAttrs = children.get(i).attributes;
            common.entrySet().removeIf(entry ->
                    !childAttrs.containsKey(entry.getKey()) ||
                    !childAttrs.get(entry.getKey()).equals(entry.getValue())
            );
        }

        return common;
    }

    private SpanNode convertToImmutable(MutableSpanNode mutable) {
        if (mutable == null) {
            return null;
        }

        List<SpanNode> children = mutable.children.stream()
                .map(this::convertToImmutable)
                .toList();

        return new SpanNode(
                mutable.spanId,
                mutable.name,
                mutable.kind,
                mutable.startTimeMs,
                mutable.durationMs,
                mutable.status,
                children,
                Map.copyOf(mutable.attributes),
                List.copyOf(mutable.issues)
        );
    }

    private TraceMetrics calculateMetrics(List<SpanData> spans) {
        int totalSpans = spans.size();
        int dbQueryCount = 0;
        long dbTotalDurationMs = 0L;
        int httpCallCount = 0;
        int errorCount = 0;

        for (SpanData span : spans) {
            if (span.hasError()) {
                errorCount++;
            }

            boolean isClient = span.kind() == Span.Kind.CLIENT;
            Map<String, String> tags = span.tags();

            if (tags != null) {
                boolean hasDbTag = tags.keySet().stream().anyMatch(k -> k.startsWith("db."));
                boolean hasHttpTag = tags.keySet().stream().anyMatch(k -> k.startsWith("http."));

                if (isClient && hasDbTag) {
                    dbQueryCount++;
                    if (span.duration() != null) {
                        dbTotalDurationMs += span.duration().toMillis();
                    }
                }

                if (isClient && hasHttpTag) {
                    httpCallCount++;
                }
            }
        }

        return new TraceMetrics(totalSpans, dbQueryCount, dbTotalDurationMs, httpCallCount, errorCount);
    }

    private TraceStatus determineStatus(List<SpanData> spans) {
        for (SpanData span : spans) {
            if (span.hasError()) {
                return TraceStatus.HAS_ERRORS;
            }
        }
        return TraceStatus.OK;
    }

    // Mutable helper class for tree building and hoisting
    private static class MutableSpanNode {
        final String spanId;
        final String name;
        final String kind;
        final long startTimeMs;
        final long durationMs;
        final String status;
        final List<MutableSpanNode> children;
        final Map<String, Object> attributes;
        final List<SpanIssue> issues;

        MutableSpanNode(String spanId, String name, String kind, long startTimeMs, long durationMs,
                        String status, List<MutableSpanNode> children,
                        Map<String, Object> attributes, List<SpanIssue> issues) {
            this.spanId = spanId;
            this.name = name;
            this.kind = kind;
            this.startTimeMs = startTimeMs;
            this.durationMs = durationMs;
            this.status = status;
            this.children = children;
            this.attributes = attributes;
            this.issues = issues;
        }
    }
}
