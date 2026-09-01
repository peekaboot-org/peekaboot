package org.peekaboot.backend.lifecycle;

import java.util.Locale;

/**
 * Renders a byte count the way a person reads one: 1024-based, one decimal, always with a
 * dot ({@code "5.8 MB"}) - the ready banner and the insights start-up line are read by
 * people and log parsers in every locale.
 */
public final class ByteFormat {

    private static final double KILO = 1024.0;

    private ByteFormat() {}

    public static String humanize(long bytes) {
        if (bytes < KILO) {
            return bytes + " B";
        }
        double kilo = bytes / KILO;
        if (kilo < KILO) {
            return String.format(Locale.ROOT, "%.1f KB", kilo);
        }
        double mega = kilo / KILO;
        if (mega < KILO) {
            return String.format(Locale.ROOT, "%.1f MB", mega);
        }
        return String.format(Locale.ROOT, "%.1f GB", mega / KILO);
    }
}
