package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.metrics.MetricGroup;
import org.peekaboot.backend.domain.metrics.MetricMeasurement;
import org.peekaboot.backend.domain.metrics.MetricStatistic;
import org.peekaboot.backend.domain.metrics.MetricsInfo;
import org.peekaboot.backend.masking.MaskingEngine;

class MetricsServiceTest {

    @Test
    void isAvailableWithAMeterRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService service = new MetricsService(registry, new MaskingEngine());

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void reportsNoMetricsWithoutAMeterRegistry() {
        MetricsService service = new MetricsService(null, new MaskingEngine());

        MetricsInfo result = service.getMetrics(null);

        assertThat(service.isAvailable()).isFalse();
        assertThat(result.metrics()).isEmpty();
    }

    @Test
    void groupsMetersByName() {
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

        MetricsInfo result = service.getMetrics(null);

        assertThat(result.metrics()).hasSize(1);

        MetricGroup group = result.metrics().get(0);
        assertThat(group.name()).isEqualTo("jvm.memory.used");
        assertThat(group.type()).isEqualTo("GAUGE");
        assertThat(group.baseUnit()).isEqualTo("bytes");
        assertThat(group.measurements()).hasSize(2);
    }

    @Test
    void reportsAGaugesValueStatistic() {
        MeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong memoryUsed = new AtomicLong(1024);

        Gauge.builder("jvm.memory.used", memoryUsed, AtomicLong::doubleValue)
                .tags("area", "heap")
                .register(registry);

        MetricMeasurement measurement = firstMeasurement(registry);

        assertThat(measurement.statistics())
                .extracting(MetricStatistic::name, MetricStatistic::value)
                .contains(tuple("VALUE", 1024.0));
    }

    /** A gauge with nothing to measure yields NaN; JSON has no NaN, so the wire carries null. */
    @Test
    void reportsANaNStatisticAsNull() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("cache.hit.ratio", () -> Double.NaN).register(registry);

        MetricMeasurement measurement = firstMeasurement(registry);

        assertThat(measurement.statistics())
                .extracting(MetricStatistic::name, MetricStatistic::value)
                .containsExactly(tuple("VALUE", null));
    }

    /** A ratio over a zero denominator is an infinity, and JSON has none either. */
    @Test
    void reportsAnInfiniteStatisticAsNull() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("ratio", () -> Double.POSITIVE_INFINITY).register(registry);

        MetricMeasurement measurement = firstMeasurement(registry);

        assertThat(measurement.statistics())
                .extracting(MetricStatistic::name, MetricStatistic::value)
                .containsExactly(tuple("VALUE", null));
    }

    @Test
    void reportsACounterWithItsDescriptionTagsAndCount() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Counter.builder("http.requests")
                .description("HTTP request count")
                .tag("method", "GET")
                .register(registry)
                .increment(42);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics(null);

        assertThat(result.metrics()).hasSize(1);
        MetricGroup group = result.metrics().get(0);
        assertThat(group.name()).isEqualTo("http.requests");
        assertThat(group.type()).isEqualTo("COUNTER");
        assertThat(group.description()).isEqualTo("HTTP request count");

        assertThat(group.measurements()).hasSize(1);
        assertThat(group.measurements().get(0).tags()).containsEntry("method", "GET");
        assertThat(group.measurements().get(0).statistics())
                .extracting(MetricStatistic::name, MetricStatistic::value)
                .containsExactly(tuple("COUNT", 42.0));
    }

    @Test
    void sortsGroupsByName() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Counter.builder("z.metric").register(registry);
        Counter.builder("a.metric").register(registry);
        Counter.builder("m.metric").register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics(null);

        assertThat(result.metrics()).extracting(MetricGroup::name).containsExactly("a.metric", "m.metric", "z.metric");
    }

    @Test
    void preservesTags() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Gauge.builder("test.metric", () -> 100)
                .tags(Tags.of("env", "prod", "region", "us-east"))
                .register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics(null);

        assertThat(result.metrics()).hasSize(1);
        assertThat(result.metrics().get(0).measurements().get(0).tags())
                .containsEntry("env", "prod")
                .containsEntry("region", "us-east");
    }

    @Test
    void listsOnlyTheMetersWhoseNameStartsWithThePrefix() {
        MetricsService service = new MetricsService(threeMeters(), new MaskingEngine());

        MetricsInfo result = service.getMetrics("jvm.");

        assertThat(result.metrics())
                .extracting(MetricGroup::name)
                .containsExactly("jvm.memory.used", "jvm.threads.live");
    }

    @Test
    void aPrefixThatMatchesNothingListsNothing() {
        MetricsService service = new MetricsService(threeMeters(), new MaskingEngine());

        MetricsInfo result = service.getMetrics("tomcat.");

        assertThat(result.metrics()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"JVM.", " jvm.", "jvm. "})
    void matchesThePrefixAsWritten(String prefix) {
        MetricsService service = new MetricsService(threeMeters(), new MaskingEngine());

        MetricsInfo result = service.getMetrics(prefix);

        assertThat(result.metrics()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  "})
    void aBlankPrefixListsEveryMeter(String prefix) {
        MetricsService service = new MetricsService(threeMeters(), new MaskingEngine());

        MetricsInfo result = service.getMetrics(prefix);

        assertThat(result.metrics())
                .extracting(MetricGroup::name)
                .containsExactly("http.server.requests", "jvm.memory.used", "jvm.threads.live");
    }

    @Test
    void aPrefixWithoutARegistryListsNothing() {
        MetricsService service = new MetricsService(null, new MaskingEngine());

        MetricsInfo result = service.getMetrics("jvm.");

        assertThat(result.metrics()).isEmpty();
    }

    /**
     * Standard Micrometer/Spring tags (area, id, uri, ...) are harmless, but a custom
     * meter registered by the consuming application can carry an arbitrary tag - the
     * same conditional risk as a custom HealthIndicator's details.
     */
    @Test
    void masksASensitiveShapedTag() {
        MeterRegistry registry = new SimpleMeterRegistry();

        Gauge.builder("custom.upstream.calls", () -> 1)
                .tags(Tags.of("api-key", "sk-abcdefghijklmnopqrstuvwxyz012345678", "region", "eu-west-1"))
                .register(registry);

        MetricsService service = new MetricsService(registry, new MaskingEngine());

        MetricsInfo result = service.getMetrics(null);

        assertThat(result.metrics().get(0).measurements().get(0).tags())
                .containsEntry("api-key", "******")
                .containsEntry("region", "eu-west-1");
    }

    /** A timer measures three statistics at once; every one reaches the wire, in the registry's base unit. */
    @Test
    void reportsEveryStatisticOfATimer() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Timer timer = Timer.builder("http.server.requests").register(registry);
        timer.record(Duration.ofMillis(120));
        timer.record(Duration.ofMillis(80));

        MetricMeasurement measurement = firstMeasurement(registry);

        assertThat(measurement.statistics())
                .extracting(MetricStatistic::name)
                .containsExactly("COUNT", "TOTAL_TIME", "MAX");
        assertThat(measurement.statistics().get(0).value()).isEqualTo(2.0);
        assertThat(measurement.statistics().get(1).value()).isCloseTo(0.2, within(1e-9));
        assertThat(measurement.statistics().get(2).value()).isCloseTo(0.12, within(1e-9));
    }

    private static MeterRegistry threeMeters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", () -> 1024).register(registry);
        Gauge.builder("jvm.threads.live", () -> 8).register(registry);
        Timer.builder("http.server.requests").register(registry);
        return registry;
    }

    /** The one measurement of the one meter a test registered. */
    private static MetricMeasurement firstMeasurement(MeterRegistry registry) {
        return new MetricsService(registry, new MaskingEngine())
                .getMetrics(null)
                .metrics()
                .get(0)
                .measurements()
                .get(0);
    }
}
