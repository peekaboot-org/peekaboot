package org.peekaboot.backend.problem;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Strict readers for the enumerated values Peekaboot's API takes. A value that names no constant is refused, never defaulted. */
public final class RequestParameters {

    private RequestParameters() {}

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
