package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives one chart series value per tick from the MeterRegistry. Meters are
 * re-resolved on every call: Micrometer registers meters lazily and new tag
 * combinations (e.g. new URIs) can appear at any time. Rates divide by the time that
 * really elapsed since the previous sample, not by the nominal tick interval.
 */
public final class SeriesSampler {

    private static final Logger log = LoggerFactory.getLogger(SeriesSampler.class);

    /**
     * Consecutive ticks a subtract-meter must stay unmatched before it is reported. Three
     * gives a lazily-registered meter (Micrometer binders resolve some meters only once the
     * thing they measure first happens) a few sampling passes to appear, while still
     * reporting a genuine typo within a small multiple of the panel's own tick interval
     * rather than letting it go unnoticed indefinitely.
     */
    private static final int UNRESOLVED_SUBTRACT_METER_WARNING_TICKS = 3;

    private final SeriesDef def;
    private final MeterRegistry registry;
    private double previousCount = Double.NaN;
    private double previousTotal = Double.NaN;
    private int consecutiveUnresolvedSubtractMeterTicks;
    private boolean subtractMeterUnresolvedLogged;

    public SeriesSampler(SeriesDef def, MeterRegistry registry) {
        this.def = def;
        this.registry = registry;
    }

    public double sample(long elapsedMillis) {
        List<Meter> meters = matching(def.meter());
        return switch (def.stat()) {
            case VALUE -> sampleValue(meters);
            case RATE -> sampleRate(meters, elapsedMillis);
            case AVG -> sampleAvg(meters);
            case MAX -> sampleMax(meters);
        };
    }

    private List<Meter> matching(String name) {
        return registry.find(name).meters().stream()
                .filter(meter -> def.tags().entrySet().stream()
                        .allMatch(tag -> tag.getValue().equals(meter.getId().getTag(tag.getKey()))))
                .toList();
    }

    private double sampleValue(List<Meter> meters) {
        double value = currentValue(meters);
        if (def.subtractMeter() == null) {
            return value;
        }
        List<Meter> subtractMeters = matching(def.subtractMeter());
        if (!meters.isEmpty()) {
            trackSubtractMeterResolution(subtractMeters.isEmpty());
        }
        double other = currentValue(subtractMeters);
        return value - other; // NaN propagates if either side is unresolved
    }

    /**
     * A series whose own meter has not resolved is already NaN and separately worth looking
     * at; warning about its subtract-meter too would only be noise on top of that, so this is
     * only called once the series' own meter did resolve.
     */
    private void trackSubtractMeterResolution(boolean unresolved) {
        if (!unresolved) {
            consecutiveUnresolvedSubtractMeterTicks = 0;
            return;
        }
        if (subtractMeterUnresolvedLogged) {
            return;
        }
        consecutiveUnresolvedSubtractMeterTicks++;
        if (consecutiveUnresolvedSubtractMeterTicks < UNRESOLVED_SUBTRACT_METER_WARNING_TICKS) {
            return;
        }
        log.warn(
                "Peekaboot insights: series '{}' (meter '{}'): subtract-meter '{}' did not resolve"
                        + " to any meter across {} ticks",
                def.id(),
                def.meter(),
                def.subtractMeter(),
                UNRESOLVED_SUBTRACT_METER_WARNING_TICKS);
        subtractMeterUnresolvedLogged = true;
    }

    private double currentValue(List<Meter> meters) {
        if (meters.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Meter meter : meters) {
            sum += switch (meter) {
                case TimeGauge tg -> tg.value(TimeUnit.SECONDS);
                case Gauge gauge -> gauge.value();
                case LongTaskTimer ltt -> ltt.activeTasks();
                case Counter counter -> counter.count();
                case FunctionCounter counter -> counter.count();
                default -> Double.NaN;
            };
        }
        return sum;
    }

    private double sampleRate(List<Meter> meters, long elapsedMillis) {
        double current = cumulativeCount(meters);
        if (Double.isNaN(current)) {
            previousCount = current;
            return Double.NaN;
        }
        if (Double.isNaN(previousCount)) {
            previousCount = current;
            return Double.NaN;
        }
        double delta = current - previousCount;
        if (delta < 0) {
            previousCount = current;
            return Double.NaN;
        }
        previousCount = current;
        return delta * 1000.0 / elapsedMillis;
    }

    private double cumulativeCount(List<Meter> meters) {
        if (meters.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Meter meter : meters) {
            sum += switch (meter) {
                case Counter counter -> counter.count();
                case FunctionCounter counter -> counter.count();
                case Timer timer -> timer.count();
                case FunctionTimer timer -> timer.count();
                case DistributionSummary summary -> summary.count();
                default -> Double.NaN;
            };
        }
        return sum;
    }

    private double sampleAvg(List<Meter> meters) {
        double currentCount = cumulativeCount(meters);
        double currentTotal = totalTime(meters);
        double previousCountBaseline = previousCount;
        double previousTotalBaseline = previousTotal;
        previousCount = currentCount;
        previousTotal = currentTotal;

        if (Double.isNaN(currentCount) || Double.isNaN(currentTotal)) {
            return Double.NaN;
        }
        if (Double.isNaN(previousCountBaseline) || Double.isNaN(previousTotalBaseline)) {
            return Double.NaN;
        }

        double deltaCount = currentCount - previousCountBaseline;
        if (deltaCount <= 0) {
            // nothing new, or a meter re-registered behind the baseline: the next
            // sample averages from the baselines just taken
            return Double.NaN;
        }
        double deltaTotal = currentTotal - previousTotalBaseline;
        return deltaTotal / deltaCount;
    }

    private double totalTime(List<Meter> meters) {
        if (meters.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Meter meter : meters) {
            sum += switch (meter) {
                case Timer timer -> timer.totalTime(TimeUnit.MILLISECONDS);
                case FunctionTimer timer -> timer.totalTime(TimeUnit.MILLISECONDS);
                case DistributionSummary summary -> summary.totalAmount();
                default -> Double.NaN;
            };
        }
        return sum;
    }

    private double sampleMax(List<Meter> meters) {
        if (meters.isEmpty()) {
            return Double.NaN;
        }
        double max = Double.NaN;
        for (Meter meter : meters) {
            double value =
                    switch (meter) {
                        case Timer timer -> timer.max(TimeUnit.MILLISECONDS);
                        case DistributionSummary summary -> summary.max();
                        default -> Double.NaN;
                    };
            if (Double.isNaN(value)) {
                continue;
            }
            max = Double.isNaN(max) ? value : Math.max(max, value);
        }
        return max;
    }
}
