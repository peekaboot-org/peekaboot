package org.peekaboot.backend.masking;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a value is sensitive and what its masked form looks like.
 *
 * <p>Two independent rule kinds:
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

    /**
     * The literal every masked value is replaced with - Spring's own {@code Sanitizer}
     * mask. Published to the frontend as {@code Features.maskLiteral}; markup.js carries
     * a fallback copy for the surfaces that never load /api/features.
     */
    public static final String MASK_LITERAL = MaskingRules.MASK;

    private static final List<List<String>> KEY_NAME_TOKEN_RULES = MaskingRules.KEY_NAME_RULES.stream()
            .map(rule -> tokenize(rule, true))
            .toList();

    private static final List<List<String>> WHOLE_KEY_NAME_TOKEN_RULES = MaskingRules.WHOLE_KEY_NAME_RULES.stream()
            .map(rule -> tokenize(rule, true))
            .toList();

    /**
     * True if {@code key} names something structurally sensitive. Matched
     * case-insensitively on whole tokens, never substrings, so "passwordless" stays
     * distinct from "password". The key is tokenized twice (see {@link #tokenize}):
     * camelCase-aware for "clientSecret", separator-only for an inconsistently cased
     * "PassWord". Order: exact-spelling exceptions ({@link MaskingRules#KEY_NAME_EXCEPTIONS},
     * "PWD"), then {@link MaskingRules#KEY_NAME_RULES} tokens anywhere in the key, then
     * {@link MaskingRules#WHOLE_KEY_NAME_RULES} as the entire key ("cookie"), then
     * {@link MaskingRules#SPRING_SANITIZER_KEY_PATTERNS}.
     */
    public boolean isSensitiveKey(String key) {
        return key != null
                && !key.isBlank()
                && !MaskingRules.KEY_NAME_EXCEPTIONS.contains(key)
                && matchesKeyNameRules(key);
    }

    /**
     * The key-name rules without the exact-spelling exceptions: those exempt a whole key
     * such as the shell's PWD variable, not a parameter name found inside a value.
     */
    private static boolean matchesKeyNameRules(String key) {
        List<String> camelAwareTokens = tokenize(key, true);
        List<String> separatorOnlyTokens = tokenize(key, false);
        return matchesAnyRuleSubsequence(camelAwareTokens, separatorOnlyTokens)
                || matchesAnyRuleExactly(WHOLE_KEY_NAME_TOKEN_RULES, camelAwareTokens, separatorOnlyTokens)
                || matchesAnySanitizerPattern(key);
    }

    private static boolean matchesAnyRuleExactly(
            List<List<String>> rules, List<String> camelAwareTokens, List<String> separatorOnlyTokens) {
        for (List<String> ruleTokens : rules) {
            if (camelAwareTokens.equals(ruleTokens) || separatorOnlyTokens.equals(ruleTokens)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyRuleSubsequence(List<String> camelAwareTokens, List<String> separatorOnlyTokens) {
        for (List<String> ruleTokens : KEY_NAME_TOKEN_RULES) {
            if (containsSubsequence(camelAwareTokens, ruleTokens)
                    || containsSubsequence(separatorOnlyTokens, ruleTokens)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnySanitizerPattern(String key) {
        for (Pattern pattern : MaskingRules.SPRING_SANITIZER_KEY_PATTERNS) {
            if (pattern.matcher(key).find()) {
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
     * Returns {@code value} verbatim when {@code unmask} is true. The decision is made once
     * upstream ({@code peekaboot.enable-unmasking} and the request's {@code unmask}
     * parameter, see {@code PeekabootController.resolveUnmask}) and threaded down; every
     * other caller uses the two-arg overload and always masks.
     */
    public String mask(String key, String value, boolean unmask) {
        return unmask ? value : mask(key, value);
    }

    /**
     * Masks a raw, still-encoded query string per parameter rather than treating it as
     * one opaque string - a whole-string regex could not tell a sensitive value from the
     * rest of the string without false positives/negatives. Each pair is decoded, masked
     * via the same {@link #mask(String, String)} rule as everywhere else, and re-encoded;
     * a bare flag with no "=" (e.g. "?debug") is passed through unchanged since it
     * carries no value to mask. A pair whose percent-encoding cannot be decoded cannot be
     * judged either, so its value is masked and the pair kept.
     */
    public String maskQueryString(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return queryString;
        }
        String[] pairs = queryString.split("&", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                result.append('&');
            }
            String pair = pairs[i];
            int equalsIndex = pair.indexOf('=');
            if (equalsIndex < 0) {
                result.append(pair);
                continue;
            }
            String rawKey = pair.substring(0, equalsIndex);
            try {
                String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(equalsIndex + 1), StandardCharsets.UTF_8);
                result.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(mask(key, value), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformedEncoding) {
                result.append(rawKey).append('=').append(MaskingRules.MASK);
            }
        }
        return result.toString();
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

        List<int[]> spans = sensitiveSpans(value);
        if (spans.isEmpty()) {
            return value;
        }

        spans.sort(Comparator.comparingInt(span -> span[0]));
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (int[] span : spans) {
            if (span[0] < cursor) {
                // spans are sorted by start offset, so the earliest-starting span wins; rule
                // order only breaks ties between spans that start at the same offset
                continue;
            }
            result.append(value, cursor, span[0]).append(MaskingRules.MASK);
            cursor = span[1];
        }
        result.append(value, cursor, value.length());
        return result.toString();
    }

    /** Every [start, end) span of {@code value} some value rule wants masked, unmerged. */
    private List<int[]> sensitiveSpans(String value) {
        List<int[]> spans = new ArrayList<>();
        for (MaskingRules.ValuePattern rule : MaskingRules.VALUE_PATTERNS) {
            Matcher matcher = rule.pattern().matcher(value);
            while (matcher.find()) {
                if (rule.keyGroup() > 0 && !matchesKeyNameRules(matcher.group(rule.keyGroup()))) {
                    continue;
                }
                int group = rule.maskGroup();
                if (group > 0 && matcher.start(group) >= 0) {
                    spans.add(new int[] {matcher.start(group), matcher.end(group)});
                } else {
                    spans.add(new int[] {matcher.start(), matcher.end()});
                }
            }
        }
        return spans;
    }

    /**
     * Splits {@code text} into lowercase tokens on dots, hyphens, underscores and any
     * other non-alphanumeric character. When {@code splitCamelCaseBoundaries} is true, a
     * boundary is also inserted at a camelCase transition (a lowercase letter or digit
     * followed by an uppercase letter) before lowercasing, so a genuine no-separator
     * compound like "clientSecret" splits into ["client", "secret"]. When false, no such
     * boundary is inserted, so a single word spelled with inconsistent casing - "PassWord"
     * - stays one token, "password", instead of being mis-split into ["pass", "word"].
     */
    private static List<String> tokenize(String text, boolean splitCamelCaseBoundaries) {
        String normalized = splitCamelCaseBoundaries ? text.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "-") : text;
        String[] parts = normalized.toLowerCase(Locale.ROOT).split("[^a-z0-9]+", -1);
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
