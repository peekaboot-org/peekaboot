package org.peekaboot.backend.service;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.domain.metrics.MetricGroup;
import org.peekaboot.backend.domain.metrics.MetricMeasurement;
import org.peekaboot.backend.domain.metrics.MetricStatistic;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TagMasker;

public class MetricsService {

    @Nullable
    private final MeterRegistry meterRegistry;

    private final TagMasker tagMasker;

    public MetricsService(@Nullable MeterRegistry meterRegistry, MaskingEngine maskingEngine) {
        this.meterRegistry = meterRegistry;
        this.tagMasker = new TagMasker(maskingEngine);
    }

    public boolean isAvailable() {
        return meterRegistry != null;
    }

    public MetricsInfo getMetrics() {
        if (meterRegistry == null) {
            return MetricsInfo.empty();
        }

        List<Meter> allMeters = meterRegistry.getMeters();
        Map<String, List<Meter>> metersByName = allMeters.stream()
                .collect(Collectors.groupingBy(meter -> meter.getId().getName(), TreeMap::new, Collectors.toList()));

        List<MetricGroup> groups = new ArrayList<>();

        for (Map.Entry<String, List<Meter>> entry : metersByName.entrySet()) {
            String name = entry.getKey();
            List<Meter> meters = entry.getValue();

            Meter firstMeter = meters.get(0);
            String description = firstMeter.getId().getDescription();
            String baseUnit = firstMeter.getId().getBaseUnit();
            String type = firstMeter.getId().getType().name();

            List<MetricMeasurement> measurements = new ArrayList<>();
            for (Meter meter : meters) {
                // a meter's tags never repeat a key; the merge function only satisfies toMap
                Map<String, String> tags = tagMasker.mask(meter.getId().getTags().stream()
                        .collect(Collectors.toMap(Tag::getKey, Tag::getValue, (v1, v2) -> v1, LinkedHashMap::new)));

                List<MetricStatistic> statistics = StreamSupport.stream(
                                meter.measure().spliterator(), false)
                        .map(measurement -> new MetricStatistic(
                                measurement.getStatistic().name(), PeekabootJson.nanToNull(measurement.getValue())))
                        .toList();

                measurements.add(new MetricMeasurement(tags, statistics));
            }

            groups.add(new MetricGroup(name, description, baseUnit, type, measurements));
        }

        return new MetricsInfo(groups.size(), allMeters.size(), groups);
    }
}
