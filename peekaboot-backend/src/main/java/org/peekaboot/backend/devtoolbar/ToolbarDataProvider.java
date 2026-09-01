package org.peekaboot.backend.devtoolbar;

import org.peekaboot.backend.config.PeekabootJson;
import tools.jackson.core.SerializableString;
import tools.jackson.core.io.CharacterEscapes;
import tools.jackson.core.io.SerializedString;
import tools.jackson.databind.ObjectWriter;

/** The JSON blob {@link ToolbarShell} embeds in the page for toolbar.js to read before it calls home. */
public class ToolbarDataProvider {

    /**
     * The blob sits verbatim inside a script element, so beyond JSON's own escapes {@code <}
     * and {@code >} are written as {@code \u003c}/{@code \u003e}: a literal
     * {@code </script>} in a request path would otherwise end the element and inject markup.
     */
    private static final ObjectWriter WRITER = PeekabootJson.MAPPER.writer().with(new ScriptSafeEscapes());

    /** What toolbar.js shows for the request the page came from. */
    record ToolbarSummary(String method, String path, int status, String traceId, String basePath) {}

    /** The page came from outside a traced request; the bar waits for the next one. */
    record IdleMode(boolean idle, String basePath) {}

    /**
     * @param basePath where the browser reaches Peekaboot from this page: the {@code /peekaboot}
     *     prefix behind the request's context path
     */
    public String getToolbarSummaryJson(String basePath, String method, String path, int status, String traceId) {
        return WRITER.writeValueAsString(new ToolbarSummary(method, path, status, traceId, basePath));
    }

    public String getIdleModeJson(String basePath) {
        return WRITER.writeValueAsString(new IdleMode(true, basePath));
    }

    private static final class ScriptSafeEscapes extends CharacterEscapes {

        private static final long serialVersionUID = 1L;

        private static final int[] ASCII_ESCAPES = standardAsciiEscapesForJSON();

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
                case '<' -> new SerializedString("\\u003c");
                case '>' -> new SerializedString("\\u003e");
                default -> null;
            };
        }
    }
}
