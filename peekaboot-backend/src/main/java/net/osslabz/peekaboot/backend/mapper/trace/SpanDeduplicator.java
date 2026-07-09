package net.osslabz.peekaboot.backend.mapper.trace;

import net.osslabz.peekaboot.backend.tracing.store.SpanData;
import net.osslabz.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SpanDeduplicator {

    private static final Set<String> SERVICE_IDENTIFIER_KEYS = Set.of("peer.service", "jdbc.datasource.name");

    public TraceData deduplicate(TraceData traceData) {
        if (traceData == null) {
            return null;
        }
        if (traceData.spans() == null || traceData.spans().isEmpty()) {
            return traceData;
        }

        List<SpanData> spans = traceData.spans();
        Map<String, SpanData> spanById = new HashMap<>();
        for (SpanData span : spans) {
            spanById.put(span.spanId(), span);
        }

        // Identify duplicate children
        Set<String> removedIds = new HashSet<>();
        for (SpanData span : spans) {
            if (span.parentId() == null || removedIds.contains(span.spanId())) {
                continue;
            }
            SpanData parent = spanById.get(span.parentId());
            if (parent == null) {
                continue;
            }
            if (isDuplicate(span, parent)) {
                removedIds.add(span.spanId());
            }
        }

        if (removedIds.isEmpty()) {
            return traceData;
        }

        // Build parent lookup for removed spans (for re-parenting)
        Map<String, String> removedParentMap = new HashMap<>();
        for (String removedId : removedIds) {
            SpanData removed = spanById.get(removedId);
            // Walk up to find a non-removed ancestor
            String newParentId = removed.parentId();
            while (newParentId != null && removedIds.contains(newParentId)) {
                newParentId = spanById.get(newParentId).parentId();
            }
            removedParentMap.put(removedId, newParentId);
        }

        // Filter and re-parent
        List<SpanData> filtered = spans.stream()
                .filter(s -> !removedIds.contains(s.spanId()))
                .map(s -> {
                    if (s.parentId() != null && removedIds.contains(s.parentId())) {
                        String newParentId = removedParentMap.get(s.parentId());
                        return reparent(s, newParentId);
                    }
                    return s;
                })
                .toList();

        return TraceData.fromSpans(traceData.traceId(), filtered);
    }

    private boolean isDuplicate(SpanData child, SpanData parent) {
        if (!child.name().equals(parent.name())) {
            return false;
        }
        return tagsMatchIgnoringServiceKeys(child.tags(), parent.tags());
    }

    private boolean tagsMatchIgnoringServiceKeys(Map<String, String> tags1, Map<String, String> tags2) {
        Map<String, String> filtered1 = filterServiceKeys(tags1);
        Map<String, String> filtered2 = filterServiceKeys(tags2);
        return filtered1.equals(filtered2);
    }

    private Map<String, String> filterServiceKeys(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> filtered = new HashMap<>(tags);
        SERVICE_IDENTIFIER_KEYS.forEach(filtered::remove);
        return filtered;
    }

    private SpanData reparent(SpanData span, String newParentId) {
        return new SpanData(
                span.traceId(), span.spanId(), newParentId, span.name(), span.kind(),
                span.startTime(), span.endTime(), span.duration(),
                span.tags(), span.events(), span.errorMessage(), span.errorClass(),
                span.remoteServiceName(), span.remoteIp(), span.remotePort(),
                span.links(), span.creationOrder()
        );
    }
}
