package org.peekaboot.backend.mapper.trace;

import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.SpanDuplicateMatcher;
import org.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SpanDeduplicator {

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
            if (SpanDuplicateMatcher.isDuplicate(span, parent)) {
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
                        return s.withParentId(newParentId);
                    }
                    return s;
                })
                .toList();

        return TraceData.fromSpans(traceData.traceId(), filtered);
    }
}
