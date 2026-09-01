package org.peekaboot.backend.devtoolbar;

public class ToolbarDataProvider {

    /** Peekaboot's UI/API prefix; also hardcoded in PeekabootController and FilterPathMatcher. */
    static final String BASE_PATH = "/peekaboot";

    public String getToolbarSummaryJson(String method, String path, int status, String traceId) {
        return String.format(
                "{\"method\":\"%s\",\"path\":\"%s\",\"status\":%d,\"traceId\":%s,\"basePath\":\"%s\"}",
                escapeJson(method),
                escapeJson(path),
                status,
                traceId != null ? "\"" + escapeJson(traceId) + "\"" : "null",
                escapeJson(BASE_PATH));
    }

    public String getIdleModeJson() {
        return String.format("{\"idle\":true,\"basePath\":\"%s\"}", escapeJson(BASE_PATH));
    }

    /**
     * Escapes a string for a JSON string literal that is embedded verbatim
     * inside a &lt;script&gt; tag: besides the JSON-mandated escapes, '&lt;' and '&gt;'
     * are escaped to prevent script-tag breakout and all remaining control
     * characters become \\uXXXX sequences to keep the JSON valid.
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            appendEscaped(sb, value.charAt(i));
        }
        return sb.toString();
    }

    private static void appendEscaped(StringBuilder sb, char c) {
        switch (c) {
            case '\\' -> sb.append("\\\\");
            case '"' -> sb.append("\\\"");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            case '<' -> sb.append("\\u003c");
            case '>' -> sb.append("\\u003e");
            default -> {
                if (c < 0x20) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
