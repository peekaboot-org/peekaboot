package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.api.IssueSeverity;
import org.peekaboot.backend.domain.features.Features;
import org.peekaboot.backend.domain.flyway.MigrationState;
import org.peekaboot.backend.domain.liquibase.ChangeSetExecType;
import org.peekaboot.backend.domain.scheduledtasks.TaskType;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.insights.config.Chart;
import org.peekaboot.backend.insights.config.TileFormat;
import org.peekaboot.backend.insights.config.Unit;
import org.peekaboot.backend.text.UptimeFormat;
import org.peekaboot.backend.ui.InlinedStylesheets;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Pins the frontend's copies of the backend's vocabularies and lists, and exercises the ES
 * modules without a test class of their own, in a real browser with each module imported from
 * the running app. Chromium blocks module imports over file://, so the served origin is what
 * makes this possible. What format.js, severity.js, http-status.js and the insights store
 * compute on their own is tested in {@link FormatModuleIT}, {@link SeverityModuleIT} and
 * {@link InsightsStoreModuleIT}.
 *
 * <p>The {@code *MirrorTheBackend*} tests are the contract tests for the vocabularies that
 * exist on both sides of the wire: the frontend is plain ES modules with no build step, so
 * nothing but these tests notices when a Java enum and its JS mirror drift apart.
 */
class SharedModuleIT extends PlaywrightTestBase {

    private static final Pattern FEATURE_READ = Pattern.compile("\\bfeatures\\??\\.([A-Za-z]+)");

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }

    /**
     * An icon made with createElement would carry the same attributes and paint nothing, so
     * the namespace is read off the element and its size off the page.
     */
    @Test
    void svgIconDrawsItsShapesAsSvgInTheSizeAsked() {
        assertThat(evalModule("dom.js", """
                (() => {
                    const icon = document.body.appendChild(m.svgIcon(
                        [['rect', {x: 3, y: 4, width: 18, height: 17, rx: 2}], ['path', {d: 'M8 2v4'}]],
                        {size: 14, strokeWidth: 2, className: 'pk-probe'}));
                    const attributes = ['class', 'viewBox', 'width', 'stroke-width', 'fill', 'stroke', 'aria-hidden'];
                    return [icon instanceof SVGSVGElement, ...[...icon.children].map(shape => shape.constructor.name),
                        icon.querySelector('rect').getAttribute('rx'), icon.getBoundingClientRect().width,
                        ...attributes.map(name => icon.getAttribute(name))];
                })()
                """))
                .isEqualTo(List.of(
                        true,
                        "SVGRectElement",
                        "SVGPathElement",
                        "2",
                        14,
                        "pk-probe",
                        "0 0 24 24",
                        "14",
                        "2",
                        "none",
                        "currentColor",
                        "true"));
    }

    @Test
    void svgIconDefaultsToTheTileSizeAndCarriesNoClass() {
        assertThat(evalModule("dom.js", """
                (() => {
                    const icon = m.svgIcon([['path', {d: 'M8 2v4'}]]);
                    return ['width', 'height', 'stroke-width'].map(name => icon.getAttribute(name))
                        .concat(icon.hasAttribute('class'));
                })()
                """)).isEqualTo(List.of("18", "18", "1.75", false));
    }

    /**
     * Every caller hands the stored locale straight to Intl, which reads undefined as the
     * browser's own locale and throws on null. Read through typeof, since both arrive in Java
     * as null.
     */
    @Test
    void anUnusableStoredLocaleReadsAsUndefined() {
        assertThat(
                        evalModule(
                                "storage.js",
                                "(() => { localStorage.removeItem('peekaboot-locale'); return typeof m.readLocaleSetting(); })()"))
                .isEqualTo("undefined");
        assertThat(
                        evalModule(
                                "storage.js",
                                "(() => { localStorage.setItem('peekaboot-locale', 'en_US'); return typeof m.readLocaleSetting(); })()"))
                .isEqualTo("undefined");
        assertThat(
                        evalModule(
                                "storage.js",
                                "(() => { localStorage.setItem('peekaboot-locale', 'de-DE'); return m.readLocaleSetting(); })()"))
                .isEqualTo("de-DE");
    }

    /**
     * Every key the served modules read off a features object is a component of the backend's
     * Features record. The reads are collected from the module sources, so a key renamed on
     * either side fails here.
     */
    @Test
    void featureKeysReadByTheFrontendMirrorTheBackendRecord() throws IOException {
        List<String> published = Arrays.stream(Features.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        Set<String> read = new TreeSet<>();
        for (Resource module :
                new PathMatchingResourcePatternResolver().getResources("classpath*:META-INF/peekaboot/ui/**/*.js")) {
            if (module.getURL().getPath().contains("/ui/vendor/")) {
                continue;
            }
            Matcher featureRead = FEATURE_READ.matcher(module.getContentAsString(StandardCharsets.UTF_8));
            while (featureRead.find()) {
                read.add(featureRead.group(1));
            }
        }

        assertThat(read).contains("tracing", "tracingSpansPossible", "maskLiteral");
        assertThat(published).containsAll(read);
    }

    @Test
    void issueSeveritiesMirrorTheBackendEnum() {
        assertThat(evalModule("severity.js", "m.ISSUE_SEVERITIES"))
                .isEqualTo(Arrays.stream(IssueSeverity.values())
                        .map(IssueSeverity::wireName)
                        .toList());
    }

    @Test
    void rootActionTypesMirrorTheBackendEnum() {
        assertThat(evalModule("root-actions.js", "m.ROOT_ACTION_TYPES")).isEqualTo(names(RootActionType.values()));
    }

    @Test
    void taskTypesMirrorTheBackendEnum() {
        assertThat(importModule("dashboard/tabs/scheduled-tasks.js", "m.TASK_TYPES"))
                .isEqualTo(names(TaskType.values()));
    }

    @Test
    void migrationStatesMirrorTheBackendEnum() {
        assertThat(evalModule("severity.js", "m.MIGRATION_STATES")).isEqualTo(names(MigrationState.values()));
    }

    @Test
    void changeSetExecTypesMirrorTheBackendEnum() {
        assertThat(evalModule("severity.js", "m.CHANGE_SET_EXEC_TYPES")).isEqualTo(names(ChangeSetExecType.values()));
    }

    /**
     * PeekabootLogbackAppender ships {@code event.getLevel().toString()}, so the levels a
     * captured line can carry are Logback's - all of them except OFF and ALL, which are
     * thresholds, never the level of an event.
     */
    @Test
    void logLevelsMirrorLogbacksEventLevels() {
        @SuppressWarnings("unchecked")
        List<String> levels = (List<String>) evalModule("severity.js", "m.LOG_LEVELS");

        assertThat(levels)
                .containsExactly(
                        Level.ERROR.toString(),
                        Level.WARN.toString(),
                        Level.INFO.toString(),
                        Level.DEBUG.toString(),
                        Level.TRACE.toString());
    }

    /**
     * Every root-action type renders an icon of its own: a plain character (an HTML entity
     * would arrive as literal text through textContent) and distinct from every sibling, so a
     * mapping that folded two types onto one glyph fails here. The characters themselves are
     * not pinned - which emoji stands for a database is a design choice, not a contract.
     */
    @Test
    void rootActionsExposeADistinctPlainCharacterIconPerType() {
        assertThat(evalModule("root-actions.js", "m.rootActionLabel('SCHEDULED_JOB')"))
                .isEqualTo("Scheduled");
        assertThat(evalModule("root-actions.js", "m.rootActionLabel('NOPE')")).isEqualTo("Unknown");

        @SuppressWarnings("unchecked")
        List<String> icons = (List<String>) evalModule("root-actions.js", "m.ROOT_ACTION_TYPES.map(m.rootActionIcon)");

        assertThat(icons).hasSameSizeAs(RootActionType.values());
        assertThat(icons).doesNotHaveDuplicates();
        assertThat(icons).allSatisfy(icon -> assertThat(icon).isNotBlank().doesNotStartWith("&"));
    }

    /**
     * The Environment and Config tabs filter on what a row actually reads: the key, or the
     * value as formatPlainValue renders it. A structured value is therefore matched by its
     * JSON, and a null value by nothing at all - the row shows "-", and matching the word
     * "null" would hand back rows whose value is precisely what the reader cannot search for.
     */
    @Test
    void propertyFilterMatchesTheRenderedValue() {
        String module = "filtered-group-tab.js";
        assertThat(evalModule(module, "m.propertyMatches({key: 'server', value: {port: 8080}}, '8080')"))
                .isEqualTo(true);
        assertThat(evalModule(module, "m.propertyMatches({key: 'server', value: {port: 8080}}, 'PORT')"))
                .isEqualTo(true);
        assertThat(evalModule(module, "m.propertyMatches({key: 'server.address', value: null}, 'null')"))
                .isEqualTo(false);
        assertThat(evalModule(module, "m.propertyMatches({key: 'server.address', value: null}, 'ADDRESS')"))
                .isEqualTo(true);
        assertThat(evalModule(module, "m.propertyMatches({key: 'spring.profiles', value: 'PROD'}, 'prod')"))
                .isEqualTo(true);
        assertThat(evalModule(module, "m.propertyMatches({key: 'server.port', value: 8080}, '')"))
                .isEqualTo(true);
    }

    /**
     * propertyGroupTab's group header count is a count like any other: grouped in the
     * context's locale. Driven straight off filteredGroupTab/propertyGroupTab rather than
     * through a whole tab (config.js/environment.js) or a route-patched response - cheaper,
     * and the header-building code is exactly the same either way.
     */
    @Test
    void propertyGroupHeaderCountsAreGroupedInTheGivenLocale() {
        Object count = evalModule("filtered-group-tab.js", """
                (() => {
                    const container = document.createElement('div');
                    container.innerHTML = '<div id="pk-fgt-list"></div><div id="pk-fgt-unmask"></div>';
                    document.body.appendChild(container);
                    const tab = m.propertyGroupTab(container, {
                        listId: 'pk-fgt-list', unmaskSlotId: 'pk-fgt-unmask',
                        select: data => data.groups,
                        emptyMessage: 'none'
                    });
                    const properties = Array.from({length: 1234}, (_, i) => ({key: 'k' + i, value: i}));
                    tab.show({groups: [{name: 'g', properties}]}, {locale: 'de-DE'});
                    return container.querySelector('.pk-group__count').textContent;
                })()
                """);

        assertThat(count).isEqualTo("1.234 properties");
    }

    /** lifecycle.js's page param is 1-based in the URL; anything unparseable, fractional or below 1 is page one. */
    @Test
    void lifecyclePageFromUrlParsesOneBasedAndRejectsGarbage() {
        assertThat(importModule("dashboard/tabs/lifecycle.js", "m.pageFromUrl({page: '3'})"))
                .isEqualTo(2);
        assertThat(importModule("dashboard/tabs/lifecycle.js", "m.pageFromUrl({page: '1'})"))
                .isEqualTo(0);
        assertThat(importModule("dashboard/tabs/lifecycle.js", "m.pageFromUrl({page: '0'})"))
                .isEqualTo(0);
        assertThat(importModule("dashboard/tabs/lifecycle.js", "m.pageFromUrl({page: '2.5'})"))
                .isEqualTo(0);
        assertThat(importModule("dashboard/tabs/lifecycle.js", "m.pageFromUrl({page: 'x'})"))
                .isEqualTo(0);
        assertThat(importModule("dashboard/tabs/lifecycle.js", "m.pageFromUrl({})"))
                .isEqualTo(0);
    }

    /** insights.js's level param must name a configured level index; anything else falls back. */
    @Test
    void insightsLevelFromUrlAcceptsOnlyConfiguredLevels() {
        String levels = "[{index: 0}, {index: 1}, {index: 2}]";
        assertThat(importModule("dashboard/tabs/insights.js", "m.levelFromUrl({level: '1'}, " + levels + ", 0)"))
                .isEqualTo(1);
        assertThat(importModule("dashboard/tabs/insights.js", "m.levelFromUrl({level: '9'}, " + levels + ", 0)"))
                .isEqualTo(0);
        assertThat(importModule("dashboard/tabs/insights.js", "m.levelFromUrl({level: 'abc'}, " + levels + ", 0)"))
                .isEqualTo(0);
        assertThat(importModule("dashboard/tabs/insights.js", "m.levelFromUrl({}, " + levels + ", 0)"))
                .isEqualTo(0);
    }

    /** insights.js's panels param: only pairs naming a configured panel at a configured level survive. */
    @Test
    void insightsPanelOverridesFromUrlAcceptOnlyConfiguredPanelsAndLevels() {
        String config = "{panels: [{id: 'cpu'}, {id: 'load'}], levels: [{index: 0}, {index: 1}]}";
        assertThat(importModule(
                        "dashboard/tabs/insights.js",
                        "JSON.stringify(m.panelOverridesFromUrl({panels: 'cpu:1,load:0'}, " + config + "))"))
                .isEqualTo("{\"cpu\":1,\"load\":0}");
        assertThat(importModule(
                        "dashboard/tabs/insights.js",
                        "JSON.stringify(m.panelOverridesFromUrl({panels: 'cpu:9'}, " + config + "))"))
                .isEqualTo("{}");
        assertThat(importModule(
                        "dashboard/tabs/insights.js",
                        "JSON.stringify(m.panelOverridesFromUrl({panels: 'ghost:1'}, " + config + "))"))
                .isEqualTo("{}");
        assertThat(importModule(
                        "dashboard/tabs/insights.js",
                        "JSON.stringify(m.panelOverridesFromUrl({panels: 'garbage'}, " + config + "))"))
                .isEqualTo("{}");
        assertThat(importModule(
                        "dashboard/tabs/insights.js",
                        "JSON.stringify(m.panelOverridesFromUrl({panels: 'cpu:'}, " + config + "))"))
                .isEqualTo("{}");
        assertThat(importModule(
                        "dashboard/tabs/insights.js", "JSON.stringify(m.panelOverridesFromUrl({}, " + config + "))"))
                .isEqualTo("{}");
    }

    /**
     * The stat line reads in the overlay's tab order - spans, queries with their time, logs -
     * then the error and warning badges. Each count shows only when there is something to count,
     * which for spans is every real trace.
     */
    @Test
    void traceStatPartsFollowTheOverlayTabOrder() {
        Object full = evalModule("trace-stats.js", """
                m.traceStatParts({counts: {spans: 84, queries: {count: 26, totalDurationMs: 31},
                    logs: {count: 27, errorCount: 1, warnCount: 2}}})
                    .map(part => part.textContent)
                """);
        Object quiet = evalModule("trace-stats.js", """
                m.traceStatParts({counts: {spans: 1, queries: {count: 0, totalDurationMs: 0},
                    logs: {count: 0, errorCount: 0, warnCount: 0}}})
                    .map(part => part.textContent)
                """);

        @SuppressWarnings("unchecked")
        List<Object> fullParts = (List<Object>) full;
        @SuppressWarnings("unchecked")
        List<Object> quietParts = (List<Object>) quiet;
        assertThat(fullParts).containsExactly("84 spans", "26 queries 31ms", "27 logs", "1 error", "2 warnings");
        assertThat(quietParts).containsExactly("1 span");
    }

    /** The stat line groups its counts in the locale it is given, like every other count. */
    @Test
    void traceStatPartsGroupCountsInTheGivenLocale() {
        Object text = evalModule("trace-stats.js", """
                m.traceStatParts({counts: {queries: {count: 1234, totalDurationMs: 5},
                    logs: {count: 0, errorCount: 2345, warnCount: 0}}}, {locale: 'de-DE'})
                    .map(part => part.textContent).join(' | ')
                """);

        assertThat((String) text).contains("1.234 queries").contains("2.345 errors");
    }

    /** The query time is a decimal figure from a second up, so it takes the decimal mark of the locale it is given. */
    @Test
    void traceStatPartsWriteTheQueryTimeInTheGivenLocale() {
        Object texts = evalModule("trace-stats.js", """
                [m.traceStatParts({counts: {queries: {count: 2, totalDurationMs: 1500}}}, {locale: 'de-DE'})
                    .map(part => part.textContent).join(' | '),
                 m.durationStat('load', 1500, '', {locale: 'de-DE'}).textContent]
                """);

        assertThat(texts).isEqualTo(List.of("2 queries 1,50s", "load 1,50s"));
    }

    /**
     * formatLongDuration mirrors the backend's UptimeFormat unit by unit, so a run's
     * duration reads identically in the log banner and on the Lifecycle tab.
     */
    @Test
    void formatLongDurationMirrorsTheBackendsUptimeFormat() {
        // The cases UptimeFormatTest pins on the Java side: singular units, an exact unit
        // standing alone, a zero unit left out rather than padded, and a run too short to
        // measure. Each is a place the two implementations could drift apart on their own.
        for (Duration uptime : List.of(
                Duration.ofSeconds(45),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofHours(2),
                Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4),
                Duration.ofDays(1).plusMinutes(3),
                Duration.ZERO)) {
            assertThat(evalModule("format.js", "m.formatLongDuration(" + uptime.toMillis() + ")"))
                    .as("%s", uptime)
                    .isEqualTo(UptimeFormat.humanize(uptime));
        }
    }

    /**
     * The panel vocabulary is typed on the backend (Chart, Unit, TileFormat) and read as
     * literals by format.js and insights-chart.js; the wire words must not drift apart.
     */
    @Test
    void panelVocabularyMirrorsTheBackendEnums() {
        assertThat(evalModule("format.js", "m.METRIC_UNITS"))
                .isEqualTo(Stream.of(Unit.values()).map(Unit::wireName).toList());
        assertThat(evalModule("format.js", "m.TILE_FORMATS"))
                .isEqualTo(
                        Stream.of(TileFormat.values()).map(TileFormat::wireName).toList());
        assertThat(importModule("dashboard/tabs/insights-chart.js", "m.CHART_TYPES"))
                .isEqualTo(Stream.of(Chart.values()).map(Chart::wireName).toList());
    }

    /**
     * insights-colors.js repeats tokens.css's light values, for a chart drawn in a document
     * whose stylesheet never applied. A recolour of a token would leave those copies painting
     * the old palette, silently and only on that path. {@code --pk-font} is out: it falls back
     * to a short system stack rather than to tokens.css's full one.
     */
    @Test
    void insightsChartFallbacksMirrorTheLightThemeTokens() {
        @SuppressWarnings("unchecked")
        Map<String, String> fallbacks = new LinkedHashMap<>(
                (Map<String, String>) importModule("dashboard/tabs/insights-colors.js", "m.LIGHT_FALLBACKS"));
        fallbacks.remove("--pk-font");

        setStoredTheme("light");
        openDashboard();

        assertThat(fallbacks)
                .isNotEmpty()
                .allSatisfy((token, fallback) -> assertThat(cssVar(":root", token))
                        .as("%s in tokens.css's light block", token)
                        .isEqualTo(fallback));
    }

    /**
     * The toolbar and the error page start from InlinedStylesheets.SHARED_SHEETS, the bar then
     * links its own: that list must name the files shadow-styles.js links into the overlay, in
     * the same order, or the server-rendered surfaces and the overlay drift apart in cascade.
     */
    @Test
    void toolbarLinksTheSharedSheetsTheOverlayLinks() {
        @SuppressWarnings("unchecked")
        List<String> shared = (List<String>) evalModule("shadow-styles.js", "m.SHARED_SHEETS");
        assertThat(InlinedStylesheets.SHARED_SHEETS)
                .isEqualTo(shared.stream().map(name -> "/ui/assets/" + name).toList());
        List<String> expected = new ArrayList<>(
                shared.stream().map(name -> "/peekaboot/ui/assets/" + name).toList());
        expected.add("/peekaboot/ui/toolbar/toolbar.css");

        openPersonsPage();
        Object linked = toolbar.evaluate(
                "root => [...root.querySelectorAll('link[rel=\"stylesheet\"]')].map(link => link.getAttribute('href'))");

        assertThat(linked).isEqualTo(expected);
    }
}
