package org.peekaboot.backend.mapper.trace;

import java.util.List;
import org.peekaboot.backend.domain.trace.TraceTree;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.tracing.store.TraceData;

/** The mappers and the calls on them that the {@link TraceTreeMapper} test classes share. */
abstract class TraceTreeMapperTestBase {

    protected final TraceTreeMapper mapper = mapperWith(SqlFormatter.NONE, new StackTraceRules(List.of(), List.of()));

    protected final TraceTreeMapper foldingMapper = mapperWith(
            SqlFormatter.NONE, new StackTraceRules(List.of("org.springframework"), List.of("org.peekaboot.app")));

    protected static TraceTreeMapper mapperWith(SqlFormatter sqlFormatter, StackTraceRules stackTraceRules) {
        MaskingEngine maskingEngine = new MaskingEngine();
        return new TraceTreeMapper(maskingEngine, sqlFormatter, stackTraceRules, new TraceRequests(maskingEngine));
    }

    protected static TraceTree map(TraceTreeMapper treeMapper, TraceData traceData) {
        return treeMapper.map(traceData, treeMapper.served(traceData));
    }

    protected static TraceTree mapSubtree(TraceTreeMapper treeMapper, TraceData traceData, String subtreeRootSpanId) {
        return treeMapper.mapSubtree(traceData, subtreeRootSpanId, treeMapper.served(traceData));
    }
}
