package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * The last reading of every meter a series matched, which turns a cumulative reading into what
 * was added since. Each meter has its own, so a tag combination that leaves or joins the match
 * reads neither as a reset nor as a spike of the whole series.
 */
final class MeterBaselines {

    private Map<Meter.Id, Double> baselines = Map.of();
    private boolean sampled;

    /**
     * What the matched meters added since each one's previous reading, summed. NaN on the first
     * sample, when nothing matched, when a reading is NaN or fell behind its baseline, and when no
     * matched meter has a known start.
     */
    double advance(List<Meter> meters, ToDoubleFunction<Meter> reading) {
        Map<Meter.Id, Double> readings = HashMap.newHashMap(meters.size());
        double added = 0;
        boolean measured = false;
        for (Meter meter : meters) {
            double current = reading.applyAsDouble(meter);
            Double baseline = baselines.get(meter.getId());
            readings.put(meter.getId(), current);
            double start;
            if (baseline != null) {
                start = baseline;
                measured = true;
            } else if (countsItsOwnEvents(meter)) {
                // registered after the previous sample, so it counted from 0
                start = 0;
                measured = true;
            } else {
                // its function may have counted long before the meter was registered
                start = current;
            }
            added += current < start ? Double.NaN : current - start;
        }
        boolean first = !sampled;
        sampled = true;
        baselines = readings;
        return first || !measured ? Double.NaN : added;
    }

    /** Drops every baseline, so the next sample is a first one. */
    void forget() {
        baselines = Map.of();
        sampled = false;
    }

    private static boolean countsItsOwnEvents(Meter meter) {
        return meter instanceof Counter || meter instanceof Timer || meter instanceof DistributionSummary;
    }
}
