package org.peekaboot.backend.mapper.trace;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.peekaboot.backend.tracing.store.SpanData;
import org.peekaboot.backend.tracing.store.SpanDuplicateMatcher;
import org.peekaboot.backend.tracing.store.TraceData;
import org.springframework.stereotype.Component;

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

        Set<String> removedIds = findDuplicateSpanIds(spans, spanById);
        if (removedIds.isEmpty()) {
            return traceData;
        }

        Map<String, String> survivingParents = resolveSurvivingAncestors(removedIds, spanById);
        List<SpanData> filtered = removeAndReparent(spans, removedIds, survivingParents);
        return TraceData.fromSpans(traceData.traceId(), filtered);
    }

    private static Set<String> findDuplicateSpanIds(List<SpanData> spans, Map<String, SpanData> spanById) {
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
        return removedIds;
    }

    /**
     * Maps each removed span to its nearest non-removed ancestor (for re-parenting).
     */
    private static Map<String, String> resolveSurvivingAncestors(
            Set<String> removedIds, Map<String, SpanData> spanById) {
        Map<String, String> survivingParents = new HashMap<>();
        for (String removedId : removedIds) {
            SpanData removed = spanById.get(removedId);
            String newParentId = removed.parentId();
            while (newParentId != null && removedIds.contains(newParentId)) {
                newParentId = spanById.get(newParentId).parentId();
            }
            survivingParents.put(removedId, newParentId);
        }
        return survivingParents;
    }

    private static List<SpanData> removeAndReparent(
            List<SpanData> spans, Set<String> removedIds, Map<String, String> survivingParents) {
        return spans.stream()
                .filter(s -> !removedIds.contains(s.spanId()))
                .map(s -> {
                    if (s.parentId() != null && removedIds.contains(s.parentId())) {
                        return s.withParentId(survivingParents.get(s.parentId()));
                    }
                    return s;
                })
                .toList();
    }
}
