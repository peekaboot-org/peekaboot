package org.peekaboot.backend.text;

import java.util.Locale;

/** Lenient lookup of an enum constant by its name, for values read from a request or an actuator response. */
public final class EnumNames {

    private EnumNames() {}

    /** Matches the name in any case; null, or a name no constant carries, gives {@code fallback}. */
    public static <E extends Enum<E>> E byName(Class<E> type, String name, E fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
