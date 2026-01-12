package net.osslabz.peekaboot.backend.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class MaskingUtil {

    private static final String MASKED_VALUE = "********";
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "x-auth-token", "x-api-key"
    );
    private static final Pattern SENSITIVE_FIELD_PATTERN = Pattern.compile(
            "(?i)(password|secret|token|key|credential|apikey|api_key)"
    );

    private MaskingUtil() {
    }

    public static Map<String, String> maskHeaders(Map<String, String> headers, boolean unmask) {
        if (headers == null) {
            return Map.of();
        }
        if (unmask) {
            return new HashMap<>(headers);
        }

        Map<String, String> masked = new HashMap<>();
        headers.forEach((key, value) -> {
            if (isSensitiveHeader(key)) {
                masked.put(key, MASKED_VALUE);
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    public static String maskJsonFields(String json, boolean unmask) {
        if (json == null || json.isBlank() || unmask) {
            return json;
        }
        return SENSITIVE_FIELD_PATTERN.matcher(json).replaceAll(match -> match.group());
    }

    public static boolean isSensitiveHeader(String headerName) {
        return headerName != null && SENSITIVE_HEADERS.contains(headerName.toLowerCase());
    }

    public static String getMaskedValue() {
        return MASKED_VALUE;
    }
}
