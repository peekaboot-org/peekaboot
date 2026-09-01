package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.metrics.MetricGroup;
import org.peekaboot.backend.domain.metrics.MetricMeasurement;
import org.peekaboot.backend.domain.metrics.MetricStatistic;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.masking.MaskingEngine;

class MetricsServiceTest {

    @Test
    void isAvailable_returnsFalse_whenNoMeterRegistry() {
        MetricsService service = new MetricsService(null, new MaskingEngine());

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_returnsTrue_whenMeterRegistryPresent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService service = new MetricsService(registry, new MaskingEngine());

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void getMetrics_returnsEmpty_whenNoMeterRegistry() {
        MetricsService service = new MetricsService(null, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        assertThat(result.metricCount()).isZero();
        assertThat(result.measurementCount()).isZero();
        assertThat(result.metrics()).isEmpty();
    }

    @Test
    void getMetrics_returnsMetrics_groupedByName() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong memoryUsed = new AtomicLong(1024);

        // Both same-name gauges get baseUnit("bytes"): MetricsService reads
        // meters.get(0) off a MeterRegistry-backed list whose iteration order
        // for same-named meters isn't guaranteed, so the baseUnit assertion
        // below must hold regardless of which of the two is "first".
        Gauge.builder("jvm.memory.used", memoryUsed, AtomicLong::doubleValue)
                .description("Memory used")
                .baseUnit("bytes")
                .tags("area", "heap", "id", "Eden")
                .register(registry);

        Gauge.builder("jvm.memory.used", () -> 2048)
                .baseUnit("bytes")
                .tags("area", "heap", "id", "Old Gen")
                .register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        assertThat(result.metricCount()).isEqualTo(1);
        assertThat(result.measurementCount()).isEqualTo(2);
        assertThat(result.metrics()).hasSize(1);

        MetricGroup group = result.metrics().get(0);
        assertThat(group.name()).isEqualTo("jvm.memory.used");
        assertThat(group.type()).isEqualTo("GAUGE");
        assertThat(group.baseUnit()).isEqualTo("bytes");
        assertThat(group.measurements()).hasSize(2);
    }

    @Test
    void getMetrics_reportsActualStatisticValueForGauge() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong memoryUsed = new AtomicLong(1024);

        Gauge.builder("jvm.memory.used", memoryUsed, AtomicLong::doubleValue)
                .tags("area", "heap")
                .register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        MetricMeasurement measurement = result.metrics().get(0).measurements().get(0);
        assertThat(measurement.statistics()).isNotEmpty();
        assertThat(measurement.statistics())
                .extracting(MetricStatistic::name, MetricStatistic::value)
                .contains(tuple("VALUE", 1024.0));
    }

    @Test
    void getMetrics_reportsActualStatisticValueForCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Counter.builder("http.requests").tag("method", "GET").register(registry).increment(42);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        MetricMeasurement measurement = result.metrics().get(0).measurements().get(0);
        assertThat(measurement.statistics()).isNotEmpty();
        assertThat(measurement.statistics())
                .extracting(MetricStatistic::name, MetricStatistic::value)
                .contains(tuple("COUNT", 42.0));
    }

    @Test
    void getMetrics_includesCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Counter.builder("http.requests")
                .description("HTTP request count")
                .tag("method", "GET")
                .register(registry)
                .increment(42);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        assertThat(result.metricCount()).isEqualTo(1);
        MetricGroup group = result.metrics().get(0);
        assertThat(group.name()).isEqualTo("http.requests");
        assertThat(group.type()).isEqualTo("COUNTER");
        assertThat(group.description()).isEqualTo("HTTP request count");

        assertThat(group.measurements()).hasSize(1);
        assertThat(group.measurements().get(0).tags()).containsEntry("method", "GET");
    }

    @Test
    void getMetrics_sortsByName() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Counter.builder("z.metric").register(registry);
        Counter.builder("a.metric").register(registry);
        Counter.builder("m.metric").register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        assertThat(result.metrics()).extracting(MetricGroup::name).containsExactly("a.metric", "m.metric", "z.metric");
    }

    @Test
    void getMetrics_preservesTags() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Gauge.builder("test.metric", () -> 100)
                .tags(Tags.of("env", "prod", "region", "us-east"))
                .register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        assertThat(result.metrics()).hasSize(1);
        assertThat(result.metrics().get(0).measurements().get(0).tags())
                .containsEntry("env", "prod")
                .containsEntry("region", "us-east");
    }

    /**
     * Standard Micrometer/Spring tags (area, id, uri, ...) are harmless, but a custom
     * meter registered by the consuming application can carry an arbitrary tag - the
     * same conditional risk as a custom HealthIndicator's details.
     */
    @Test
    void getMetrics_masksASensitiveShapedTag() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Gauge.builder("custom.upstream.calls", () -> 1)
                .tags(Tags.of("api-key", "sk-abcdefghijklmnopqrstuvwxyz012345678", "region", "eu-west-1"))
                .register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics();

        assertThat(result.metrics().get(0).measurements().get(0).tags())
                .containsEntry("api-key", "******")
                .containsEntry("region", "eu-west-1");
    }
}
