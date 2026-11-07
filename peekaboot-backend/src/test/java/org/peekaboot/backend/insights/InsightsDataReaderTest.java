package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.problem.ApiProblemException;
import org.springframework.core.io.DefaultResourceLoader;

/** The insights data endpoint's reading of the rings: the window, the series, the two views and the refusals. */
class InsightsDataReaderTest {

    private final AtomicReference<Double> heapUsed = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> cpu = new AtomicReference<>(0.5);
    private InsightsService service;
    private InsightsDataReader reader;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // a supplier, never a weakly held state object: a GC between ticks would turn samples into NaN
        Gauge.builder("jvm.memory.used", heapUsed::get).tag("area", "heap").register(registry);
        Gauge.builder("process.cpu.usage", cpu::get).register(registry);
        InsightsProperties properties = new InsightsProperties();
        service = new InsightsService(
                registry, properties, new DefaultResourceLoader(), InsightsCollectors.noOpListener(), null);
        reader = new InsightsDataReader(service, properties, 1000);
    }

    /** Ticks at 10 s, 20 s, 30 s and so on, the heap at {@code values}. */
    private void tickHeap(double... values) {
        for (int i = 0; i < values.length; i++) {
            heapUsed.set(values[i]);
            service.collector().tick((i + 1) * 10_000L);
        }
    }

    private InsightsDataResponse read(String level, String view, String series, String since) {
        return reader.read(new InsightsDataParameters(level, view, series, since));
    }

    @Test
    void theSummaryViewServesEachSeriesStatisticsAndNoSamples() {
        tickHeap(10, 30, 20);

        InsightsDataResponse data = read("0", null, null, null);

        InsightsDataResponse.SeriesData heap = data.series().get("heap.used");
        assertThat(heap.summary()).isEqualTo(new SeriesSummary(10.0, 20.0, 30.0, 20.0, 30.0, 20.0, 3));
        assertThat(heap.values()).isNull();
        assertThat(heap.stats()).isNull();
        assertThat(data.timestamps()).isNull();
        assertThat(data.count()).isEqualTo(3);
    }

    @Test
    void theFullViewAddsTheSamplesAndWhenEachWasTaken() {
        tickHeap(10, 30, 20);

        InsightsDataResponse data = read("0", "full", null, null);

        assertThat(data.series().get("heap.used").values()).containsExactly(10.0, 30.0, 20.0);
        assertThat(data.timestamps()).containsExactly(10_000L, 20_000L, 30_000L);
        assertThat(data.endEpochMs()).isEqualTo(30_000L);
    }

    @Test
    void sinceKeepsTheSamplesTakenAtOrAfterIt() {
        tickHeap(10, 30, 20);

        InsightsDataResponse data = read("0", "full", null, "1970-01-01T00:00:20Z");

        assertThat(data.count()).isEqualTo(2);
        assertThat(data.timestamps()).containsExactly(20_000L, 30_000L);
        assertThat(data.series().get("heap.used").values()).containsExactly(30.0, 20.0);
        assertThat(data.series().get("heap.used").summary().min()).isEqualTo(20.0);
    }

    @Test
    void aSinceAfterTheNewestSampleLeavesAnEmptyWindow() {
        tickHeap(10, 30, 20);

        InsightsDataResponse data = read("0", "full", null, "1970-01-01T00:00:31Z");

        assertThat(data.count()).isZero();
        assertThat(data.timestamps()).isEmpty();
        assertThat(data.series().get("heap.used").summary())
                .isEqualTo(new SeriesSummary(null, null, null, null, null, null, 0));
    }

    @Test
    void seriesNarrowsTheResponseToTheIdsItNamesInPanelOrder() {
        tickHeap(10);

        InsightsDataResponse data = read("0", null, "heap.used,cpu.process", null);

        assertThat(data.series().keySet()).containsExactly("cpu.process", "heap.used");
    }

    /** A gap leaves as null, since JSON has no NaN. */
    @Test
    void anUnresolvedSeriesServesNullSamplesAndAnEmptySummary() {
        service.collector().tick(10_000);

        InsightsDataResponse.SeriesData heap =
                read("0", "full", null, null).series().get("heap.used");

        assertThat(heap.values()).containsExactly((Double) null);
        assertThat(heap.summary().samples()).isZero();
        assertThat(heap.summary().latest()).isNull();
    }

    /** The problems read every series, so a request that narrows the series still learns what the window shows. */
    @Test
    void theProblemsCoverEverySeriesWhateverTheRequestNarrows() {
        cpu.set(0.9);
        tickHeap(10);

        InsightsDataResponse data = read("0", null, "heap.used", null);

        assertThat(data.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.rule()).isEqualTo(ProblemRule.CPU_HIGH);
            assertThat(problem.value()).isCloseTo(90.0, within(1e-9));
        });
    }

    @Test
    void anUnknownSeriesIsRefusedNamingItAndTheAcceptedIds() {
        assertThatThrownBy(() -> read("0", null, "heap.usd", null))
                .isInstanceOfSatisfying(ApiProblemException.class, e -> {
                    assertThat(e.problem().parameter()).isEqualTo("series");
                    assertThat(e.problem().detail())
                            .startsWith("Unknown series heap.usd. Accepted: cpu.process, cpu.system,");
                });
    }

    @Test
    void anEmptySeriesIdIsRefused() {
        assertThatThrownBy(() -> read("0", null, "heap.used,,cpu.process", null))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().detail())
                                .isEqualTo(
                                        "series names an empty id in 'heap.used,,cpu.process'. Separate series ids with"
                                                + " commas."));
    }

    @Test
    void anUnknownViewIsRefusedNamingTheAcceptedValues() {
        assertThatThrownBy(() -> read("0", "all", null, null)).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.problem().parameter()).isEqualTo("view");
            assertThat(e.problem().detail()).isEqualTo("Unknown view 'all'. Accepted: SUMMARY, FULL.");
        });
    }

    @Test
    void aSinceThatIsNoInstantIsRefused() {
        assertThatThrownBy(() -> read("0", null, null, "yesterday"))
                .isInstanceOfSatisfying(
                        ApiProblemException.class,
                        e -> assertThat(e.problem().parameter()).isEqualTo("since"));
    }

    @Test
    void aLevelOutsideTheConfiguredOnesIsRefusedNamingTheRange() {
        assertThatThrownBy(() -> read("7", null, null, null)).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.problem().status()).isEqualTo(400);
            assertThat(e.problem().parameter()).isEqualTo("level");
            assertThat(e.problem().detail()).isEqualTo("level must be an integer from 0 to 2, got '7'.");
        });
    }

    @Test
    void aMissingLevelIsRefusedNamingTheAcceptedRange() {
        assertThatThrownBy(() -> read(null, null, null, null)).isInstanceOfSatisfying(ApiProblemException.class, e -> {
            assertThat(e.problem().parameter()).isEqualTo("level");
            assertThat(e.problem().detail()).isEqualTo("level is required. Accepted: 0 to 2.");
        });
    }

    @Test
    void aNegativeOrNonNumericLevelIsRefused() {
        assertThatThrownBy(() -> read("-1", null, null, null)).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> read("abc", null, null, null)).isInstanceOf(ApiProblemException.class);
    }
}
