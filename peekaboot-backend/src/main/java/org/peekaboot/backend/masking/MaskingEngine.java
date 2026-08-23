package org.peekaboot.backend.masking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a value is sensitive and what its masked form looks like.
 *
 * <p>Two independent rule kinds, both from the design spec:
 * <ul>
 *     <li><b>Key-name rules</b> - if the key (a property name, a header name, ...) names
 *     something structurally sensitive (a password, a token, ...), the whole value is
 *     replaced.</li>
 *     <li><b>Value rules</b> - high-precision, provider-prefixed patterns that catch a
 *     credential embedded in a value under an innocuous key (a JDBC URL's password, a
 *     bearer token in a header, a key pasted into SQL text). Only the matched span is
 *     replaced, so the rest of the value stays useful.</li>
 * </ul>
 *
 * <p>Deliberately shaped so a caller with no key at all (SQL text, a log line) can still
 * run the value rules via {@link #maskValue(String)}; a caller with a key/value pair -
 * a property or an HTTP header - uses {@link #mask(String, String)}, which checks the key
 * first and falls back to the value rules only when the key itself isn't sensitive.
 *
 * <p>Pure logic: no Spring wiring, no I/O, no state beyond the compiled rules in
 * {@link MaskingRules}.
 */
public final class MaskingEngine {

    private static final List<List<String>> KEY_NAME_TOKEN_RULES = MaskingRules.KEY_NAME_RULES.stream()
        .map(MaskingEngine::tokenize)
        .toList();

    /**
     * True if {@code key} names something structurally sensitive - matched
     * case-insensitively, anywhere in the key, on a separator boundary (dot, hyphen,
     * underscore or a camelCase transition all count).
     */
    public boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        List<String> tokens = tokenize(key);
        for (List<String> ruleTokens : KEY_NAME_TOKEN_RULES) {
            if (containsSubsequence(tokens, ruleTokens)) {
                return true;
            }
        }
        for (Pattern legacyPattern : MaskingRules.LEGACY_KEY_PATTERNS) {
            if (legacyPattern.matcher(key).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Masks {@code value} for the given {@code key}. A sensitive key masks the value
     * outright; an innocuous (or absent, {@code null}) key falls back to
     * {@link #maskValue(String)}, so a credential embedded under a harmless key name -
     * a JDBC URL under {@code spring.datasource.url} - is still caught.
     */
    public String mask(String key, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitiveKey(key)) {
            return MaskingRules.MASK;
        }
        return maskValue(value);
    }

    /**
     * Runs the value rules against {@code value} with no key in play at all - a bare
     * value such as SQL text or a log line. Masks only the matched span(s); the rest of
     * the value is returned untouched.
     */
    public String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        List<int[]> spans = new ArrayList<>();
        for (MaskingRules.ValuePattern rule : MaskingRules.VALUE_PATTERNS) {
            Matcher matcher = rule.pattern().matcher(value);
            while (matcher.find()) {
                int group = rule.maskGroup();
                if (group > 0 && matcher.start(group) >= 0) {
                    spans.add(new int[]{matcher.start(group), matcher.end(group)});
                } else {
                    spans.add(new int[]{matcher.start(), matcher.end()});
                }
            }
        }
        if (spans.isEmpty()) {
            return value;
        }

        spans.sort(Comparator.comparingInt(span -> span[0]));
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            if (span[0] < cursor) {
                // overlaps a span already masked by an earlier (higher-precedence) rule
                continue;
            }
            result.append(value, cursor, span[0]).append(MaskingRules.MASK);
            cursor = span[1];
        }
        result.append(value, cursor, value.length());
        return result.toString();
    }

    /**
     * Splits {@code text} into lowercase tokens on dots, hyphens, underscores, any other
     * non-alphanumeric character, and camelCase transitions (a lowercase/digit followed
     * by an uppercase letter).
     */
    private static List<String> tokenize(String text) {
        String withCamelBoundaries = text.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "-");
        String[] parts = withCamelBoundaries.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /** True if {@code needle} occurs as a contiguous run somewhere in {@code haystack}. */
    private static boolean containsSubsequence(List<String> haystack, List<String> needle) {
        int haystackSize = haystack.size();
        int needleSize = needle.size();
        if (needleSize == 0 || needleSize > haystackSize) {
            return false;
        }
        for (int start = 0; start <= haystackSize - needleSize; start++) {
            if (haystack.subList(start, start + needleSize).equals(needle)) {
                return true;
            }
        }
        return false;
    }
}
