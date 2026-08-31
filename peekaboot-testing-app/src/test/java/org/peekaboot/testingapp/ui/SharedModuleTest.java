package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Exercises the shared ES modules in a real browser, imported from the running
 * app. Chromium blocks module imports over file://, so the served origin is
 * what makes this possible.
 */
class SharedModuleTest extends PlaywrightTestBase {

    private Object evalModule(String module, String expression) {
        if (!page.url().equals(baseUrl + "/peekaboot/ui/pk-blank.html")) {
            page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");
        }
        return page.evaluate(
                "async ([mod, expr]) => { const m = await import(mod); return eval(expr); }",
                java.util.List.of("/peekaboot/ui/shared/" + module, expression));
    }

    @Test
    void escapeHtmlNeutralisesMarkup() {
        assertThat(evalModule("markup.js", "m.escapeHtml('<img src=x onerror=alert(1)>')"))
                .isEqualTo("&lt;img src=x onerror=alert(1)&gt;");
    }

    @Test
    void escapeHtmlTreatsNullAsEmpty() {
        assertThat(evalModule("markup.js", "m.escapeHtml(null)")).isEqualTo("");
    }

    @Test
    void escapeHtmlEscapesAmpersandsAndQuotesForAttributeContext() {
        assertThat(evalModule("markup.js", "m.escapeHtml('<script>alert(1)<' + '/script> & more')"))
                .isEqualTo("&lt;script&gt;alert(1)&lt;/script&gt; &amp; more");
        assertThat(evalModule("markup.js", "m.escapeHtml('\" onmouseover=\"alert(1)')"))
                .isEqualTo("&quot; onmouseover=&quot;alert(1)");
        assertThat(evalModule("markup.js", "m.escapeHtml(\"' onmouseover='alert(1)\")"))
                .isEqualTo("&#39; onmouseover=&#39;alert(1)");
    }

    @Test
    void escapeHtmlTreatsUndefinedAsEmptyButPreservesFalsyZero() {
        assertThat(evalModule("markup.js", "m.escapeHtml(undefined)")).isEqualTo("");
        assertThat(evalModule("markup.js", "m.escapeHtml(0)")).isEqualTo("0");
    }

    @Test
    void highlightTextWrapsEveryMatchAndEscapesTheRest() {
        assertThat(evalModule("markup.js", "m.highlightText('a<b>a', 'a')"))
                .isEqualTo("<mark>a</mark>&lt;b&gt;<mark>a</mark>");
    }

    @Test
    void formatDurationScalesByMagnitude() {
        assertThat(evalModule("format.js", "m.formatDurationMs(0.4)")).isEqualTo("<1ms");
        assertThat(evalModule("format.js", "m.formatDurationMs(250)")).isEqualTo("250ms");
        assertThat(evalModule("format.js", "m.formatDurationMs(1500)")).isEqualTo("1.50s");
        assertThat(evalModule("format.js", "m.formatDurationMs(90000)")).isEqualTo("1.50m");
        assertThat(evalModule("format.js", "m.formatDurationMs(null)")).isEqualTo("-");
    }

    @Test
    void formatDurationRejectsNegativeValues() {
        assertThat(evalModule("format.js", "m.formatDurationMs(-5)")).isEqualTo("-");
    }

    @Test
    void formatBytesScalesByMagnitude() {
        assertThat(evalModule("format.js", "m.formatBytes(0)")).isEqualTo("0 B");
        assertThat(evalModule("format.js", "m.formatBytes(1536)")).isEqualTo("1.50 KB");
        assertThat(evalModule("format.js", "m.formatBytes(-1)")).isEqualTo("-");
    }

    @Test
    void formatBytesHandlesFractionalValuesBelowOne() {
        // Math.log(0.5)/Math.log(1024) is negative, so the exponent must be clamped
        // at zero instead of indexing BYTE_UNITS with -1.
        assertThat(evalModule("format.js", "m.formatBytes(0.5)")).isEqualTo("0.50 B");
    }

    @Test
    void formatHostsRendersHostnamePortAndFallbacks() {
        // API host objects carry "hostname" (net.osslabz.jdbc.Host), not "host"
        assertThat(evalModule("format.js", "m.formatHosts([{hostname: '127.0.0.1', port: 5432, instanceName: null}])"))
                .isEqualTo("127.0.0.1:5432");
        assertThat(evalModule(
                        "format.js", "m.formatHosts([{hostname: 'db1', port: 5432}, {hostname: 'db2', port: 5433}])"))
                .isEqualTo("db1:5432, db2:5433");
        assertThat(evalModule("format.js", "m.formatHosts([{hostname: 'db.local'}])"))
                .isEqualTo("db.local");
        assertThat(evalModule("format.js", "m.formatHosts(null)")).isEqualTo("unknown");
        assertThat(evalModule("format.js", "m.formatHosts([])")).isEqualTo("unknown");
    }

    @Test
    void durationSeverityUsesOneSetOfThresholds() {
        assertThat(evalModule("severity.js", "m.durationSeverity(50)")).isEqualTo("");
        assertThat(evalModule("severity.js", "m.durationSeverity(101)")).isEqualTo("slow");
        assertThat(evalModule("severity.js", "m.durationSeverity(501)")).isEqualTo("very-slow");
        assertThat(evalModule("severity.js", "m.SLOW_MS")).isEqualTo(100);
        assertThat(evalModule("severity.js", "m.VERY_SLOW_MS")).isEqualTo(500);
    }

    @Test
    void durationSeverityAtExactlyTheSlowThresholdIsNotYetSlow() {
        // Pins the boundary itself (ms > SLOW_MS, not >=) so an off-by-one in the
        // comparison fails here instead of only showing up on values well past it.
        assertThat(evalModule("severity.js", "m.durationSeverity(100)")).isEqualTo("");
    }

    @Test
    void healthSeverityMapsActuatorStatuses() {
        assertThat(evalModule("severity.js", "m.healthSeverity('UP')")).isEqualTo("ok");
        assertThat(evalModule("severity.js", "m.healthSeverity('DOWN')")).isEqualTo("error");
        assertThat(evalModule("severity.js", "m.healthSeverity('WHATEVER')")).isEqualTo("muted");
    }

    @Test
    void rootActionsExposeIconsAsPlainCharacters() {
        assertThat(evalModule("root-actions.js", "m.rootActionLabel('SCHEDULED_JOB')"))
                .isEqualTo("Scheduled Job");
        assertThat(evalModule("root-actions.js", "m.rootActionLabel('NOPE')")).isEqualTo("Unknown");
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('HTTP_REQUEST').startsWith('&')"))
                .isEqualTo(false);
        assertThat(evalModule("root-actions.js", "m.ROOT_ACTION_TYPES.length")).isEqualTo(7);
    }

    @Test
    void rootActionIconsMatchTheExpectedLiteralCharacters() {
        // Pins every icon explicitly so a mapping swap (e.g. DATABASE <-> RPC_CALL)
        // fails here instead of slipping through on the "not an entity" check alone.
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('HTTP_REQUEST') === '\\u{1F310}'"))
                .isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('SCHEDULED_JOB') === '\\u{1F551}'"))
                .isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('MESSAGE_CONSUMER') === '\\u{1F4E9}'"))
                .isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('RPC_CALL') === '\\u{1F517}'"))
                .isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('DATABASE') === '\\u{1F5C2}'"))
                .isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('INTERNAL') === '⚙'"))
                .isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('UNKNOWN') === '❓'"))
                .isEqualTo(true);
    }

    @Test
    void formatDateTimeHandlesUnparseableValueWithoutSayingInvalidDate() {
        Object result = evalModule("format.js", "m.formatDateTime('not-a-timestamp')");
        assertThat(result).isEqualTo("not-a-timestamp");
        assertThat((String) result).doesNotContain("Invalid Date");
    }

    @Test
    void formatTimeOfDayHandlesUnparseableValueWithoutSayingInvalidDate() {
        Object result = evalModule("format.js", "m.formatTimeOfDay('not-a-timestamp')");
        assertThat(result).isEqualTo("not-a-timestamp");
        assertThat((String) result).doesNotContain("Invalid Date");
    }

    @Test
    void formatDateTimeTreatsEpochZeroAsAValidTimestamp() {
        assertThat(evalModule("format.js", "m.formatDateTime(0, {locale: 'en-US', timeZone: 'UTC'})"))
                .isNotEqualTo("-");
    }

    @Test
    void formatTimeOfDayTreatsEpochZeroAsAValidTimestamp() {
        assertThat(evalModule("format.js", "m.formatTimeOfDay(0, {locale: 'en-US', timeZone: 'UTC'})"))
                .isNotEqualTo("-");
    }

    /**
     * Regression guard for the overlay header meta line always reading "1 queries" - a
     * bare "+ 's'" pluralisation with no singular/plural distinction. formatCount()'s
     * plural defaults to singular + 's' (covers "span"/"spans", "log"/"logs") but takes an
     * explicit override for irregular nouns like "query"/"queries".
     */
    @Test
    void formatCountPluralisesIrregularNouns() {
        assertThat(evalModule("format.js", "m.formatCount(1, 'query', 'queries')"))
                .isEqualTo("1 query");
        assertThat(evalModule("format.js", "m.formatCount(2, 'query', 'queries')"))
                .isEqualTo("2 queries");
        assertThat(evalModule("format.js", "m.formatCount(1, 'span')")).isEqualTo("1 span");
        assertThat(evalModule("format.js", "m.formatCount(0, 'span')")).isEqualTo("0 spans");
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
