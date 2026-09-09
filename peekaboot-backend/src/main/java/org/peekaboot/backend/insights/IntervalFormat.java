package org.peekaboot.backend.insights;

import java.time.Duration;

/** Renders a ring interval compactly: whole hours as "Nh", whole minutes as "Nm", else "Ns" or "Nms". */
final class IntervalFormat {

    private IntervalFormat() {}

    static String humanize(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 3_600_000 == 0) {
            return (millis / 3_600_000) + "h";
        }
        if (millis % 60_000 == 0) {
            return (millis / 60_000) + "m";
        }
        if (millis % 1_000 == 0) {
            return (millis / 1_000) + "s";
        }
        return millis + "ms";
    }
}
