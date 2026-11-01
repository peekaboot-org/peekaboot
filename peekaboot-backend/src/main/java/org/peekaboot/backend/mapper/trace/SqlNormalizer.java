package org.peekaboot.backend.mapper.trace;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A statement with its literals and its layout taken out, so two runs of the same query compare equal.
 *
 * <p>It does not understand backslash escapes, the {@code E''}, {@code B''} and {@code U&''} prefixes, hex
 * and leading-dot numbers, signs, {@code TRUE}/{@code FALSE} or quoted identifiers, so those can
 * normalise unevenly.
 */
final class SqlNormalizer {

    /**
     * A quoted literal, a doubled quote inside it included; the prefix covers hex and national strings.
     * The possessive quantifiers keep the match iterative, so a long literal cannot overflow the stack.
     * An unterminated literal matches to the end with an empty group 1, so no quote inside it starts
     * another scan.
     */
    private static final Pattern STRING_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_$])[xXnN]?'[^']*+(?:''[^']*+)*+('|\\z)");

    /**
     * Digits inside an identifier ({@code co1_0}) or after {@code $} (a placeholder) are not literals.
     * Digits after a dot are never a number of their own, so {@code .5} stays as written.
     */
    private static final Pattern NUMBER_LITERAL =
            Pattern.compile("(?<![A-Za-z0-9_$.])\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?(?![A-Za-z0-9_$])");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SqlNormalizer() {}

    /** Strings go first, so the digits inside them never reach the number pattern. */
    static String normalize(String sql) {
        String withoutStrings = STRING_LITERAL
                .matcher(sql)
                .replaceAll(literal -> literal.group(1).isEmpty() ? Matcher.quoteReplacement(literal.group()) : "?");
        String withoutNumbers = NUMBER_LITERAL.matcher(withoutStrings).replaceAll("?");
        return WHITESPACE.matcher(withoutNumbers).replaceAll(" ").strip();
    }
}
