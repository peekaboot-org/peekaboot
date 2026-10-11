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
import java.util.function.ToDoubleFunction;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Derives one chart series value per tick from the MeterRegistry. Meters are
 * re-resolved on every call: Micrometer registers meters lazily and new tag
 * combinations (e.g. new URIs) can appear at any time. Rates divide by the time that
 * really elapsed since the previous sample, not by the nominal tick interval.
 */
public final class SeriesSampler {

    private static final Logger log = LoggerFactory.getLogger(SeriesSampler.class);

    /**
     * How a meter that stays unresolved is reported. An operator's own series warns, since
     * an unknown name there is most likely a typo. A shipped series logs at debug: the
     * application may simply not have that meter (no DataSource, no request served yet).
     */
    public enum UnresolvedMeterReport {
        WARN(Level.WARN),
        DEBUG(Level.DEBUG),
        NONE(null);

        private final Level level;

        UnresolvedMeterReport(Level level) {
            this.level = level;
        }
    }

    /**
     * Consecutive ticks a meter must stay unmatched before it is reported. Three
     * gives a lazily-registered meter (Micrometer binders resolve some meters only once the
     * thing they measure first happens) a few sampling passes to appear, while still
     * reporting a genuine typo within a small multiple of the panel's own tick interval
     * rather than letting it go unnoticed indefinitely.
     */
    private static final int UNRESOLVED_METER_REPORT_TICKS = 3;

    private final SeriesDef def;
    private final MeterRegistry registry;
    private double previousCount = Double.NaN;
    private double previousTotal = Double.NaN;
    private final Outage meterOutage;
    private final Outage subtractMeterOutage;

    public SeriesSampler(SeriesDef def, MeterRegistry registry, UnresolvedMeterReport report) {
        this.def = def;
        this.registry = registry;
        this.meterOutage = new Outage(
                report,
                "Peekaboot insights: series '{}': meter '{}' did not resolve to any meter across {} ticks",
                def.id(),
                def.meter(),
                UNRESOLVED_METER_REPORT_TICKS);
        this.subtractMeterOutage = new Outage(
                report,
                "Peekaboot insights: series '{}' (meter '{}'): subtract-meter '{}' did not resolve"
                        + " to any meter across {} ticks",
                def.id(),
                def.meter(),
                def.subtractMeter(),
                UNRESOLVED_METER_REPORT_TICKS);
    }

    public double sample(long elapsedMillis) {
        List<Meter> meters = matching(def.meter());
        meterOutage.track(meters.isEmpty());
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
        // a series whose own meter is unresolved is reported as such; its subtract-meter on top would be noise
        if (!meters.isEmpty()) {
            subtractMeterOutage.track(subtractMeters.isEmpty());
        }
        double other = currentValue(subtractMeters);
        return value - other; // NaN propagates if either side is unresolved
    }

    /**
     * Reports a meter once it has stayed unresolved for {@link #UNRESOLVED_METER_REPORT_TICKS}
     * consecutive ticks. Resolving re-arms it, so every outage is reported, not just the first.
     */
    private static final class Outage {

        private final UnresolvedMeterReport report;
        private final String message;
        private final Object[] arguments;
        private int consecutiveUnresolvedTicks;
        private boolean reported;

        private Outage(UnresolvedMeterReport report, String message, Object... arguments) {
            this.report = report;
            this.message = message;
            this.arguments = arguments;
        }

        private void track(boolean unresolved) {
            if (!unresolved) {
                consecutiveUnresolvedTicks = 0;
                reported = false;
                return;
            }
            if (reported || report.level == null) {
                return;
            }
            consecutiveUnresolvedTicks++;
            if (consecutiveUnresolvedTicks < UNRESOLVED_METER_REPORT_TICKS) {
                return;
            }
            log.atLevel(report.level).log(message, arguments);
            reported = true;
        }
    }

    private static double currentValue(List<Meter> meters) {
        return sum(meters, meter -> switch (meter) {
            case TimeGauge tg -> tg.value(TimeUnit.SECONDS);
            case Gauge gauge -> gauge.value();
            case LongTaskTimer ltt -> ltt.activeTasks();
            case Counter counter -> counter.count();
            case FunctionCounter counter -> counter.count();
            default -> Double.NaN;
        });
    }

    /** The readings summed across the tag combinations a meter name matched; NaN when nothing matched. */
    private static double sum(List<Meter> meters, ToDoubleFunction<Meter> reading) {
        if (meters.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        for (Meter meter : meters) {
            sum += reading.applyAsDouble(meter);
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

    private static double cumulativeCount(List<Meter> meters) {
        return sum(meters, meter -> switch (meter) {
            case Counter counter -> counter.count();
            case FunctionCounter counter -> counter.count();
            case Timer timer -> timer.count();
            case FunctionTimer timer -> timer.count();
            case DistributionSummary summary -> summary.count();
            default -> Double.NaN;
        });
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

    private static double totalTime(List<Meter> meters) {
        return sum(meters, meter -> switch (meter) {
            case Timer timer -> timer.totalTime(TimeUnit.MILLISECONDS);
            case FunctionTimer timer -> timer.totalTime(TimeUnit.MILLISECONDS);
            case DistributionSummary summary -> summary.totalAmount();
            default -> Double.NaN;
        });
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
