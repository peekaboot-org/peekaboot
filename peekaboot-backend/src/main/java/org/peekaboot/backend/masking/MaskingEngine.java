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
        .map(rule -> tokenize(rule, true))
        .toList();

    private static final List<List<String>> WHOLE_KEY_NAME_TOKEN_RULES = MaskingRules.WHOLE_KEY_NAME_RULES.stream()
        .map(rule -> tokenize(rule, true))
        .toList();

    private static final List<List<String>> KEY_NAME_EXCEPTION_TOKENS = MaskingRules.KEY_NAME_EXCEPTIONS.stream()
        .map(rule -> tokenize(rule, true))
        .toList();

    /**
     * True if {@code key} names something structurally sensitive - matched
     * case-insensitively, anywhere in the key, on a separator boundary (dot, hyphen,
     * underscore or a camelCase transition all count).
     *
     * <p>The key is tokenized two ways and a rule match on either is enough. The
     * camelCase-aware tokenization ({@link #tokenize(String, boolean)} with splitting on)
     * catches a genuine no-separator compound name ("clientSecret"). On its own it would
     * also mis-split an inconsistently-cased spelling of a single-word rule -
     * "PassWord" becomes ["pass", "word"], neither of which is "password" - because it
     * cannot tell "a new word starts here" from "this word merely has a stray internal
     * capital", and Spring's relaxed binding means the caller doesn't control how an
     * external property source spells a key. The separator-only tokenization (splitting
     * off) doesn't attempt that distinction at all: it lowercases first, so "PassWord"
     * collapses to the single token "password" and matches directly. Both tokenizations
     * still require an exact, whole-token match - never a substring - so this fallback
     * doesn't reopen the over-matching a bare substring check would cause (e.g.
     * "passwordless" staying a distinct token from "password" either way).
     *
     * <p>Three refinements sit on top of that token-anywhere check, in order:
     * {@link MaskingRules#KEY_NAME_EXCEPTIONS} short-circuits a single-token key to
     * "not sensitive" even though it would otherwise match a rule word exactly (see
     * {@link MaskingRules} for why "PWD" needs this and "db.pwd" doesn't); then the
     * ordinary {@link MaskingRules#KEY_NAME_RULES} match anywhere in the key,
     * subsequence-style, same as always; then {@link MaskingRules#WHOLE_KEY_NAME_RULES}
     * match only when the rule's tokens are the *entire* key, not merely present in it -
     * "cookie" is sensitive as an HTTP header name but not as a token buried inside
     * server.servlet.session.cookie.same-site.
     */
    public boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        List<String> camelAwareTokens = tokenize(key, true);
        List<String> separatorOnlyTokens = tokenize(key, false);
        for (List<String> exceptionTokens : KEY_NAME_EXCEPTION_TOKENS) {
            if (camelAwareTokens.equals(exceptionTokens) || separatorOnlyTokens.equals(exceptionTokens)) {
                return false;
            }
        }
        for (List<String> ruleTokens : KEY_NAME_TOKEN_RULES) {
            if (containsSubsequence(camelAwareTokens, ruleTokens)
                    || containsSubsequence(separatorOnlyTokens, ruleTokens)) {
                return true;
            }
        }
        for (List<String> ruleTokens : WHOLE_KEY_NAME_TOKEN_RULES) {
            if (camelAwareTokens.equals(ruleTokens) || separatorOnlyTokens.equals(ruleTokens)) {
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
     * Same as {@link #mask(String, String)}, except when {@code unmask} is true, in which
     * case masking is bypassed entirely and {@code value} is returned verbatim. Exists so
     * the controlled-unmasking decision - already made once, upstream, from
     * {@code peekaboot.enable-unmasking} and the request's {@code unmask} parameter - can
     * be threaded down to this call without re-deriving it here. {@code unmask} defaulting
     * to {@code false} (Java's primitive default) means a caller that passes nothing masks,
     * the same default-deny the two-arg {@link #mask(String, String)} already gives every
     * caller that isn't part of the unmasking feature at all.
     */
    public String mask(String key, String value, boolean unmask) {
        return unmask ? value : mask(key, value);
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
     * Splits {@code text} into lowercase tokens on dots, hyphens, underscores and any
     * other non-alphanumeric character. When {@code splitCamelCaseBoundaries} is true, a
     * boundary is also inserted at a camelCase transition (a lowercase letter or digit
     * followed by an uppercase letter) before lowercasing, so a genuine no-separator
     * compound like "clientSecret" splits into ["client", "secret"]. When false, no such
     * boundary is inserted, so a single word spelled with inconsistent casing - "PassWord"
     * - stays one token, "password", instead of being mis-split into ["pass", "word"].
     */
    private static List<String> tokenize(String text, boolean splitCamelCaseBoundaries) {
        String normalized = splitCamelCaseBoundaries
            ? text.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "-")
            : text;
        String[] parts = normalized.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
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
