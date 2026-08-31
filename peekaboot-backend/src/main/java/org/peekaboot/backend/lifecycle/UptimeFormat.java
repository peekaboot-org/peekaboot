package org.peekaboot.backend.lifecycle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders how long something ran the way a person would say it: the three largest
 * units that actually have a value ("1 day, 2 hours, 3 minutes"), and plain seconds
 * for anything under a minute.
 */
public final class UptimeFormat {

    private static final int MAX_UNITS = 3;

    private UptimeFormat() {}

    public static String humanize(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return "0 seconds";
        }
        List<String> parts = new ArrayList<>(MAX_UNITS);
        append(parts, duration.toDays(), "day");
        append(parts, duration.toHoursPart(), "hour");
        append(parts, duration.toMinutesPart(), "minute");
        append(parts, duration.toSecondsPart(), "second");
        if (parts.isEmpty()) {
            return "0 seconds";
        }
        return String.join(", ", parts.subList(0, Math.min(parts.size(), MAX_UNITS)));
    }

    private static void append(List<String> parts, long value, String unit) {
        if (value > 0) {
            parts.add(value + " " + unit + (value == 1 ? "" : "s"));
        }
    }
}
