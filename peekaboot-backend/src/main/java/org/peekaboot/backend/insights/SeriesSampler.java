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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.peekaboot.backend.insights.config.MeterAlternative;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * Derives one chart series value per tick from the MeterRegistry. Meters are
 * re-resolved on every call: Micrometer registers meters lazily and new tag
 * combinations (e.g. new URIs) can appear at any time. Each tick reads the first of the
 * series' meter and its alternatives that has a meter carrying every one of that
 * candidate's tag keys, whatever the values, so a 5xx series stays on its meter before the
 * first 5xx. A rate divides by the elapsed time its caller passes. The collector passes the
 * time since level 0's last stamped boundary, and the nominal interval for the first sample.
 *
 * <p>Every value is served in the unit its series declares. A time gauge reads in
 * milliseconds. A percent series reads a ratio from 0 to 1, as Micrometer's CPU and GC
 * meters report one, and serves it from 0 to 100.
 */
final class SeriesSampler {

    private static final Logger log = LoggerFactory.getLogger(SeriesSampler.class);

    /**
     * How a meter that stays unresolved is reported. An operator's own series warns, since
     * an unknown name there is most likely a typo. A shipped series logs at debug: the
     * application may simply not have that meter (no DataSource, no request served yet).
     */
    enum UnresolvedMeterReport {
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

    private static final double PERCENT_PER_RATIO = 100.0;

    private final SeriesDef def;
    private final List<MeterAlternative> candidates;
    private final String candidateMeters;
    private final MeterRegistry registry;
    private final MeterBaselines counts = new MeterBaselines();
    private final MeterBaselines totals = new MeterBaselines();
    private final Outage meterOutage;
    private final Outage subtractMeterOutage;
    private boolean readFailureReported;

    SeriesSampler(SeriesDef def, MeterRegistry registry, UnresolvedMeterReport report) {
        this.def = def;
        this.candidates = Stream.concat(
                        Stream.of(new MeterAlternative(def.meter(), def.tags())), def.alternatives().stream())
                .toList();
        this.candidateMeters = candidates.stream()
                .map(candidate -> "'" + candidate.meter() + "'")
                .collect(Collectors.joining(" or "));
        this.registry = registry;
        this.meterOutage = new Outage(
                report,
                "Peekaboot insights: series '{}': meter {} did not resolve to any meter across {} ticks",
                def.id(),
                candidateMeters,
                UNRESOLVED_METER_REPORT_TICKS);
        this.subtractMeterOutage = new Outage(
                report,
                "Peekaboot insights: series '{}' (meter {}): subtract-meter '{}' did not resolve"
                        + " to any meter across {} ticks",
                def.id(),
                candidateMeters,
                def.subtractMeter(),
                UNRESOLVED_METER_REPORT_TICKS);
    }

    /**
     * The series' value over the last {@code elapsedMillis}, NaN when it has none. A meter whose
     * read throws is a NaN as well, reported once per outage, so it costs only this series.
     */
    double sample(long elapsedMillis) {
        try {
            double value = read(elapsedMillis);
            readFailureReported = false;
            return value;
        } catch (RuntimeException e) {
            // the next rate would divide what several intervals counted by one interval
            counts.forget();
            totals.forget();
            reportReadFailure(e);
            return Double.NaN;
        }
    }

    private void reportReadFailure(RuntimeException failure) {
        if (readFailureReported) {
            return;
        }
        log.warn(
                "Peekaboot insights: series '{}': reading meter {} failed; recording a gap until it reads again",
                def.id(),
                candidateMeters,
                failure);
        readFailureReported = true;
    }

    private double read(long elapsedMillis) {
        MeterAlternative candidate = presentCandidate();
        List<Meter> meters = matching(candidate.meter(), candidate.tags());
        meterOutage.track(meters.isEmpty());
        double value =
                switch (def.stat()) {
                    case VALUE -> sampleValue(meters, candidate.tags());
                    case RATE -> sampleRate(meters, elapsedMillis);
                    case AVG -> sampleAvg(meters);
                    case MAX -> sampleMax(meters);
                };
        return def.unit() == Unit.PERCENT ? value * PERCENT_PER_RATIO : value;
    }

    /** The first candidate present in the registry; the series' own meter when none is, so it reports unresolved. */
    private MeterAlternative presentCandidate() {
        return candidates.stream()
                .filter(candidate -> !registry.find(candidate.meter())
                        .tagKeys(candidate.tags().keySet())
                        .meters()
                        .isEmpty())
                .findFirst()
                .orElse(candidates.getFirst());
    }

    private List<Meter> matching(String name, Map<String, String> tags) {
        return registry.find(name).meters().stream()
                .filter(meter -> tags.entrySet().stream()
                        .allMatch(tag -> tag.getValue().equals(meter.getId().getTag(tag.getKey()))))
                .toList();
    }

    private double sampleValue(List<Meter> meters, Map<String, String> tags) {
        double value = currentValue(meters);
        if (def.subtractMeter() == null) {
            return value;
        }
        List<Meter> subtractMeters = matching(def.subtractMeter(), tags);
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
            case TimeGauge tg -> tg.value(TimeUnit.MILLISECONDS);
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
        return counts.advance(meters, SeriesSampler::cumulativeCount) * 1000.0 / elapsedMillis;
    }

    private static double cumulativeCount(Meter meter) {
        return switch (meter) {
            case Counter counter -> counter.count();
            case FunctionCounter counter -> counter.count();
            case Timer timer -> timer.count();
            case FunctionTimer timer -> timer.count();
            case DistributionSummary summary -> summary.count();
            default -> Double.NaN;
        };
    }

    private double sampleAvg(List<Meter> meters) {
        double addedCount = counts.advance(meters, SeriesSampler::cumulativeCount);
        double addedTotal = totals.advance(meters, SeriesSampler::totalTime);
        // no average without a recording; an unknown count is NaN and fails the comparison too
        return addedCount > 0 ? addedTotal / addedCount : Double.NaN;
    }

    private static double totalTime(Meter meter) {
        return switch (meter) {
            case Timer timer -> timer.totalTime(TimeUnit.MILLISECONDS);
            case FunctionTimer timer -> timer.totalTime(TimeUnit.MILLISECONDS);
            case DistributionSummary summary -> summary.totalAmount();
            default -> Double.NaN;
        };
    }

    private double sampleMax(List<Meter> meters) {
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
