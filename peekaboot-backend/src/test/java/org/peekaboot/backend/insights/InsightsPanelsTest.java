package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.PanelDef;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.Unit;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

class InsightsPanelsTest {

    @Test
    void aDisabledPanelIsDroppedWithItsSeries() {
        InsightsPanels panels = InsightsPanels.load(new DefaultResourceLoader(), new InsightsProperties());

        assertThat(panels.panels())
                .extracting(PanelDef::id)
                .contains("cpu", "heap")
                .doesNotContain("thread-states");
        assertThat(panels.series()).extracting(SeriesDef::id).doesNotContain("thread-states.runnable");
        assertThat(panels.tiles()).hasSize(4);
    }

    /** The sampler scales by the unit a series carries, so one that names none has to leave with its panel's. */
    @Test
    void aSeriesIsNamespacedAndCarriesItsPanelsUnitWhereItNamesNone() {
        InsightsPanels panels = InsightsPanels.load(new DefaultResourceLoader(), new InsightsProperties());

        assertThat(panels.series())
                .filteredOn(series -> series.id().equals("cpu.process"))
                .singleElement()
                .extracting(SeriesDef::unit)
                .isEqualTo(Unit.PERCENT);
        assertThat(panels.series())
                .filteredOn(series -> series.id().equals("gc.max-pause"))
                .singleElement()
                .extracting(SeriesDef::unit)
                .isEqualTo(Unit.MILLIS);
    }

    /**
     * A series is shipped while it is exactly as bundled: in a panel the operator left alone
     * and in one patched by id alone. A series the operator wrote is theirs.
     */
    @Test
    void onlyTheSeriesLeftAsBundledAreShipped() {
        InsightsPanels panels = withUserPanels("classpath:insights/user-series-provenance.yml");

        assertThat(panels.shippedSeriesIds())
                .contains("load.load1m", "db-pool.total")
                .doesNotContain("custom.typo");
        assertThat(panels.series()).extracting(SeriesDef::id).contains("custom.typo");
    }

    /**
     * The panel file is the user's, not ours: a typo in it may cost them the Insights
     * tab's customisation, but never the application it is embedded in.
     */
    @Test
    void invalidUserPanelFileIsIgnoredInFavourOfTheDefaults() {
        try (LogCapture logs = LogCapture.attach(InsightsPanels.class)) {
            InsightsPanels fallback = withUserPanels("classpath:insights/loader-invalid.yml");

            assertThat(fallback.panels())
                    .extracting(PanelDef::id)
                    .as("the bundled panels, none of them from the broken file")
                    .contains("cpu", "heap")
                    .doesNotContain("broken");
            assertThat(logs.appender().list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("loader-invalid.yml");
                assertThat(event.getThrowableProxy().getMessage()).contains("bogus");
            });
        }
    }

    /**
     * PanelConfigLoader rejects subtract-meter on a non-value stat at load time, and that
     * exception takes the same user-override path as any other invalid file - a typo and a
     * rejected combination cost the operator their whole panel customisation exactly alike,
     * with the stock panels served in its place.
     */
    @Test
    void aSubtractMeterOnANonValueStatCostsTheWholeUserOverride() {
        try (LogCapture logs = LogCapture.attach(InsightsPanels.class)) {
            InsightsPanels fallback = withUserPanels("classpath:insights/loader-subtract-meter-rate.yml");

            assertThat(fallback.panels())
                    .extracting(PanelDef::id)
                    .as("the bundled panels, none of them from the rejected file")
                    .contains("cpu", "heap")
                    .doesNotContain("net");
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("loader-subtract-meter-rate.yml")
                        .contains("discarding it entirely")
                        .contains("bundled panels");
                assertThat(event.getThrowableProxy().getMessage()).contains("subtract-meter");
            });
        }
    }

    /** The loader refuses two panels that spell one series id, and that costs what any other broken file costs. */
    @Test
    void aCollidingUserPanelCostsOnlyTheCustomisation() {
        try (LogCapture logs = LogCapture.attach(InsightsPanels.class)) {
            InsightsPanels fallback = withUserPanels("classpath:insights/loader-colliding-series.yml");

            assertThat(fallback.panels())
                    .extracting(PanelDef::id)
                    .contains("cpu")
                    .doesNotContain("a", "a.b");
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("loader-colliding-series.yml");
                assertThat(event.getThrowableProxy().getMessage()).contains("series id 'a.b.c'");
            });
        }
    }

    /** A location the operator set and that names nothing is a mistake in the property; the fallback would hide it. */
    @Test
    void aConfigLocationThatDoesNotExistStopsTheStartNamingIt() {
        assertThatIllegalStateException()
                .isThrownBy(() -> withUserPanels("classpath:insights/no-such-panels.yml"))
                .withMessage("Insights panel config class path resource [insights/no-such-panels.yml] does not exist."
                        + " Check peekaboot.insights.config-location.");
    }

    /** A panel's level picks one of the configured rings; one that names a ring that does not exist has nothing to chart. */
    @Test
    void aPanelLevelBeyondTheConfiguredLevelsIsRejectedLikeAnyOtherConfigError() {
        assertThatThrownBy(() ->
                        InsightsPanels.load(bundling("insights/loader-unknown-level.yml"), new InsightsProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("panel 'deep': level 7");
    }

    @Test
    void aUserPanelLevelBeyondTheConfiguredLevelsCostsOnlyTheCustomisation() {
        try (LogCapture logs = LogCapture.attach(InsightsPanels.class)) {
            InsightsPanels fallback = withUserPanels("classpath:insights/loader-unknown-level.yml");

            assertThat(fallback.panels())
                    .extracting(PanelDef::id)
                    .contains("cpu", "heap")
                    .doesNotContain("deep");
            assertThat(logs.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getThrowableProxy().getMessage()).contains("panel 'deep': level 7");
            });
        }
    }

    /** A broken file of ours is a bug of ours, and must not be papered over. */
    @Test
    void invalidBundledPanelFileFailsFast() {
        assertThatThrownBy(() -> InsightsPanels.load(bundling("insights/loader-invalid.yml"), new InsightsProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bogus");
    }

    /** The bundled panels plus the operator's override at {@code location}. */
    private static InsightsPanels withUserPanels(String location) {
        InsightsProperties properties = new InsightsProperties();
        properties.setConfigLocation(location);
        return InsightsPanels.load(new DefaultResourceLoader(), properties);
    }

    /** A loader that answers the request for the bundled panels with the class path file {@code path}. */
    private static ResourceLoader bundling(String path) {
        return new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return location.contains("peekaboot-insights-defaults")
                        ? new ClassPathResource(path)
                        : super.getResource(location);
            }
        };
    }
}
