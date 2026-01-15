package net.osslabz.peekaboot.backend.devtoolbar;

public class ToolbarDataProvider {

    private final String basePath;

    public ToolbarDataProvider(String basePath) {
        this.basePath = basePath;
    }

    public String getToolbarSummaryJson(String method, String path, int status, String traceId) {
        return String.format(
                "{\"method\":\"%s\",\"path\":\"%s\",\"status\":%d,\"traceId\":%s,\"basePath\":\"%s\"}",
                escapeJson(method),
                escapeJson(path),
                status,
                traceId != null ? "\"" + escapeJson(traceId) + "\"" : "null",
                escapeJson(basePath)
        );
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }
}
