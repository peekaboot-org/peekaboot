package org.peekaboot.backend.insights.config;

import java.util.Locale;

/**
 * The word a vocabulary constant travels under: its name in lower case, hyphenated
 * ({@code BARS_LINE} is {@code bars-line}). The binder reads the panel file's words
 * into the constants by the same relaxed form.
 */
final class WireNames {

    private WireNames() {}

    static String of(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
