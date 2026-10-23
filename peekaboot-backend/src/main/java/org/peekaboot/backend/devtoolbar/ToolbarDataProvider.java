package org.peekaboot.backend.devtoolbar;

import java.util.List;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import tools.jackson.core.SerializableString;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.io.SerializedString;
import tools.jackson.databind.ObjectWriter;

/** The JSON blob {@link ToolbarShell} embeds in the page for toolbar.js to read before it calls home. */
public class ToolbarDataProvider {

    /**
     * The blob sits verbatim inside a script element, so beyond JSON's own escapes
     * {@code <} and {@code >} are written as the unicode escapes {@code ScriptSafeEscapes}
     * holds: a literal {@code </script>} in a request path would otherwise end the element
     * and inject markup.
     */
    private static final ObjectWriter WRITER = PeekabootJson.MAPPER.writer().with(new ScriptSafeEscapes());

    private final PeekabootTracingProperties tracingProperties;

    public ToolbarDataProvider(PeekabootTracingProperties tracingProperties) {
        this.tracingProperties = tracingProperties;
    }

    /**
     * The effective thresholds and the mask literal, under the names {@code /api/features}
     * publishes them: the bar never fetches that endpoint, and the overlay it opens colours
     * and highlights by these.
     */
    record ToolbarFeatures(
            long slowSpanThresholdMs, long verySlowSpanThresholdMs, long slowQueryThresholdMs, String maskLiteral) {}

    /** What toolbar.js shows for the request the page came from. */
    record ToolbarSummary(
            String method, String path, int status, String traceId, String basePath, ToolbarFeatures features) {}

    /**
     * The page came from outside a traced request; the bar waits for the next one, skipping the
     * calls under {@code ignoredPathPrefixes}.
     */
    record IdleMode(boolean idle, String basePath, List<String> ignoredPathPrefixes, ToolbarFeatures features) {}

    /**
     * What the bar shows for a page that came out of a traced request.
     *
     * @param basePath where the browser reaches Peekaboot from this page: the {@code /peekaboot}
     *     prefix behind the request's context path
     */
    public String getToolbarSummaryJson(String basePath, String method, String path, int status, String traceId) {
        return WRITER.writeValueAsString(new ToolbarSummary(method, path, status, traceId, basePath, features()));
    }

    /**
     * What the bar shows on Swagger UI, which has no request of its own.
     *
     * @param ignoredPathPrefixes the paths, as the browser addresses them, whose calls the bar does not report
     */
    public String getIdleModeJson(String basePath, List<String> ignoredPathPrefixes) {
        return WRITER.writeValueAsString(new IdleMode(true, basePath, ignoredPathPrefixes, features()));
    }

    private ToolbarFeatures features() {
        return new ToolbarFeatures(
                tracingProperties.getSlowSpanThresholdMs(),
                tracingProperties.getVerySlowSpanThresholdMs(),
                tracingProperties.getSlowQueryThresholdMs(),
                MaskingEngine.MASK_LITERAL);
    }

    private static final class ScriptSafeEscapes extends CharacterEscapes {

        private static final long serialVersionUID = 1L;

        private static final int[] ASCII_ESCAPES = standardAsciiEscapesForJSON();
        private static final SerializedString LESS_THAN = new SerializedString("\\u003c");
        private static final SerializedString GREATER_THAN = new SerializedString("\\u003e");

        static {
            ASCII_ESCAPES['<'] = ESCAPE_CUSTOM;
            ASCII_ESCAPES['>'] = ESCAPE_CUSTOM;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return ASCII_ESCAPES;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return switch (ch) {
                case '<' -> LESS_THAN;
                case '>' -> GREATER_THAN;
                default -> null;
            };
        }
    }
}
