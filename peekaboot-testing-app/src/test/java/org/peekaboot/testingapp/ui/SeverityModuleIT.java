package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises shared/severity.js and shared/http-status.js in a real browser, imported from the
 * running app.
 */
class SeverityModuleIT extends PlaywrightTestBase {

    @Test
    void querySeverityFollowsTheQueryThreshold() {
        String features = "{slowQueryThresholdMs: 80}";
        assertThat(evalModule("severity.js", "m.querySeverity(null, " + features + ")"))
                .isEqualTo("");
        assertThat(evalModule("severity.js", "m.querySeverity(79, " + features + ")"))
                .isEqualTo("");
        assertThat(evalModule("severity.js", "m.querySeverity(80, " + features + ")"))
                .isEqualTo("slow");
    }

    @Test
    void durationSeverityReadsThePublishedThresholds() {
        String features = "{slowSpanThresholdMs: 200, verySlowSpanThresholdMs: 300}";
        assertThat(evalModule("severity.js", "m.durationSeverity(null, " + features + ")"))
                .isEqualTo("");
        assertThat(evalModule("severity.js", "m.durationSeverity(150, " + features + ")"))
                .isEqualTo("");
        assertThat(evalModule("severity.js", "m.durationSeverity(250, " + features + ")"))
                .isEqualTo("slow");
        assertThat(evalModule("severity.js", "m.durationSeverity(350, " + features + ")"))
                .isEqualTo("very-slow");
    }

    @Test
    void durationSeverityAtExactlyTheThresholdIsAlreadySlow() {
        // Pins the boundary itself (ms >= threshold, matching IssueDetector's >=) so an
        // off-by-one in the comparison fails here instead of only showing up on values
        // well past it.
        String features = "{slowSpanThresholdMs: 100, verySlowSpanThresholdMs: 500}";
        assertThat(evalModule("severity.js", "m.durationSeverity(99, " + features + ")"))
                .isEqualTo("");
        assertThat(evalModule("severity.js", "m.durationSeverity(100, " + features + ")"))
                .isEqualTo("slow");
        assertThat(evalModule("severity.js", "m.durationSeverity(500, " + features + ")"))
                .isEqualTo("very-slow");
    }

    /**
     * The tier is read off the backend's own severity word, so an issue type the frontend has never
     * heard of still colours, as long as it is measured in milliseconds. A count or a status says
     * nothing about how long the span took.
     */
    @Test
    void issueSeverityReportsTheWorstOfASpansLatencyIssues() {
        assertThat(evalModule(
                        "severity.js", "m.issueSeverity([{type: 'SLOW_QUERY', severity: 'warning', unit: 'ms'}])"))
                .isEqualTo("slow");
        assertThat(
                        evalModule(
                                "severity.js",
                                "m.issueSeverity([{type: 'SLOW', severity: 'warning', unit: 'ms'}, {type: 'VERY_SLOW', severity: 'error', unit: 'ms'}])"))
                .isEqualTo("very-slow");
        assertThat(evalModule(
                        "severity.js", "m.issueSeverity([{type: 'NOT_YET_INVENTED', severity: 'error', unit: 'ms'}])"))
                .isEqualTo("very-slow");
        assertThat(evalModule(
                        "severity.js",
                        "m.issueSeverity([{type: 'REPEATED_QUERY', severity: 'warning', unit: 'queries'}])"))
                .isEqualTo("");
        assertThat(evalModule(
                        "severity.js",
                        "m.issueSeverity([{type: 'CLIENT_ERROR', severity: 'warning', unit: 'status'}])"))
                .isEqualTo("");
        assertThat(evalModule("severity.js", "m.issueSeverity([])")).isEqualTo("");
        assertThat(evalModule("severity.js", "m.issueSeverity(undefined)")).isEqualTo("");
    }

    /** An issue with no duration to colour shows as a badge in its severity's tier; an unknown word stays muted. */
    @Test
    void issueBadgeVariantFollowsTheSeverityWord() {
        assertThat(evalModule("severity.js", "['warning', 'error', 'unheard-of'].map(m.issueBadgeVariant)"))
                .isEqualTo(List.of("warn", "error", "muted"));
    }

    @Test
    void changeSetExecTypeVariantMarksOnlyAFailureAsAnError() {
        assertThat(evalModule(
                        "severity.js",
                        "['EXECUTED', 'RERAN', 'FAILED', 'SKIPPED', 'MARK_RAN', 'UNKNOWN', 'NEW_TYPE', undefined]"
                                + ".map(type => m.changeSetExecTypeVariant(type))"))
                .isEqualTo(List.of("ok", "ok", "error", "muted", "muted", "muted", "muted", "muted"));
    }

    @Test
    void logLevelVariantGivesTheThreeLoudLevelsTheirOwnTier() {
        assertThat(evalModule("severity.js", "m.logLevelVariant('ERROR')")).isEqualTo("error");
        assertThat(evalModule("severity.js", "m.logLevelVariant('WARN')")).isEqualTo("warn");
        assertThat(evalModule("severity.js", "m.logLevelVariant('INFO')")).isEqualTo("info");
        assertThat(evalModule("severity.js", "m.logLevelVariant('DEBUG')")).isEqualTo("muted");
        assertThat(evalModule("severity.js", "m.logLevelVariant('TRACE')")).isEqualTo("muted");
        assertThat(evalModule("severity.js", "m.logLevelVariant(undefined)")).isEqualTo("muted");
    }

    @Test
    void healthSeverityMapsActuatorStatuses() {
        assertThat(evalModule("severity.js", "m.healthSeverity('UP')")).isEqualTo("ok");
        assertThat(evalModule("severity.js", "m.healthSeverity('DOWN')")).isEqualTo("error");
        assertThat(evalModule("severity.js", "m.healthSeverity('OUT_OF_SERVICE')"))
                .isEqualTo("error");
        assertThat(evalModule("severity.js", "m.healthSeverity('WHATEVER')")).isEqualTo("muted");
    }

    @Test
    void statusLabelSpellsOutTheReasonPhrase() {
        assertThat(evalModule("http-status.js", "m.statusLabel(200)")).isEqualTo("200 OK");
        assertThat(evalModule("http-status.js", "m.statusLabel(301)")).isEqualTo("301 Moved Permanently");
        assertThat(evalModule("http-status.js", "m.statusLabel(404)")).isEqualTo("404 Not Found");
        assertThat(evalModule("http-status.js", "m.statusLabel(500)")).isEqualTo("500 Internal Server Error");
    }

    /**
     * A status arrives from the API as a number but from a span tag as a string; both
     * spell out the same way, so neither call site has to coerce before asking.
     */
    @Test
    void statusLabelAcceptsANumericString() {
        assertThat(evalModule("http-status.js", "m.statusLabel('404')")).isEqualTo("404 Not Found");
    }

    /**
     * The registry only covers the codes IANA has assigned. A vendor-specific code
     * still has to render as itself rather than as a blank or an invented phrase.
     */
    @Test
    void statusLabelFallsBackToTheBareCodeWhenNoPhraseIsRegistered() {
        assertThat(evalModule("http-status.js", "m.statusLabel(599)")).isEqualTo("599");
    }

    @Test
    void statusLabelPassesThroughAPlaceholderForATraceWithNoHttpStatus() {
        assertThat(evalModule("http-status.js", "m.statusLabel('-')")).isEqualTo("-");
        assertThat(evalModule("http-status.js", "m.statusLabel(null)")).isEqualTo("-");
    }

    @Test
    void statusVariantGivesEachResponseFamilyItsOwnBadgeTier() {
        assertThat(evalModule("http-status.js", "m.statusVariant(204)")).isEqualTo("ok");
        assertThat(evalModule("http-status.js", "m.statusVariant(301)")).isEqualTo("warn");
        assertThat(evalModule("http-status.js", "m.statusVariant(404)")).isEqualTo("error-soft");
        assertThat(evalModule("http-status.js", "m.statusVariant(500)")).isEqualTo("error");
    }

    /**
     * A trace with no HTTP exchange at all (a scheduled job, say) has no status to
     * colour - it must not borrow the 5xx tier just because it is not a 2xx.
     */
    @Test
    void statusVariantIsMutedWhenThereIsNoStatusToColour() {
        assertThat(evalModule("http-status.js", "m.statusVariant('-')")).isEqualTo("muted");
        assertThat(evalModule("http-status.js", "m.statusVariant(null)")).isEqualTo("muted");
    }
}
