package org.peekaboot.backend.problem;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Strict readers for the values Peekaboot's API takes. A value it cannot read is refused, never defaulted. */
public final class RequestParameters {

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d{1,10}");

    private RequestParameters() {}

    /** {@code value} unless it is null or blank, which a request sends for a parameter it leaves empty. */
    @Nullable
    public static String text(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** {@code value} as an integer from {@code min} to {@code max}; null where it is absent or blank. */
    @Nullable
    public static Integer integer(String parameter, @Nullable String value, int min, int max) {
        String present = text(value);
        if (present == null) {
            return null;
        }
        String digits = present.strip();
        long parsed = INTEGER.matcher(digits).matches() ? Long.parseLong(digits) : Long.MIN_VALUE;
        if (parsed < min || parsed > max) {
            throw ApiProblemException.badParameter(
                    parameter, parameter + " must be an integer from " + min + " to " + max + ", got '" + value + "'.");
        }
        return (int) parsed;
    }

    /**
     * {@code value} as an ISO-8601 instant, with {@code Z} or an offset; null where it is absent or blank.
     * A {@code +} left unencoded in a query string arrives as a space, so the refusal says how to send one.
     */
    @Nullable
    public static Instant instant(String parameter, @Nullable String value) {
        String present = text(value);
        if (present == null) {
            return null;
        }
        Instant parsed = parseOrNull(present.strip());
        if (parsed == null) {
            throw ApiProblemException.badParameter(
                    parameter,
                    parameter + " must be an ISO-8601 instant such as 2026-11-01T10:15:30Z, got '" + value
                            + "'. Send a + in an offset as %2B.");
        }
        return parsed;
    }

    @Nullable
    private static Instant parseOrNull(String candidate) {
        try {
            return Instant.parse(candidate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code value} as one of {@code type}'s constants, in any case; {@code alsoAccepted} are listed with them in the refusal. */
    public static <E extends Enum<E>> E enumValue(
            String parameter, Class<E> type, String value, String... alsoAccepted) {
        String name = value.strip().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) {
                return constant;
            }
        }
        String accepted = Stream.concat(
                        Arrays.stream(type.getEnumConstants()).map(Enum::name), Arrays.stream(alsoAccepted))
                .collect(Collectors.joining(", "));
        throw ApiProblemException.badParameter(
                parameter, "Unknown " + parameter + " '" + value + "'. Accepted: " + accepted + ".");
    }
}
