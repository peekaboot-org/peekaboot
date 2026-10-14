package org.peekaboot.backend.ui;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills {@code {{NAME}}} placeholders in one left-to-right pass. A value is never scanned again,
 * so request text that spells a placeholder lands verbatim instead of being filled in. A name the
 * map does not carry stays as written, so a template can be filled in stages.
 */
public final class Placeholders {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private Placeholders() {}

    public static String fill(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder filled = new StringBuilder(template.length());
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(filled, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(filled);
        return filled.toString();
    }
}
