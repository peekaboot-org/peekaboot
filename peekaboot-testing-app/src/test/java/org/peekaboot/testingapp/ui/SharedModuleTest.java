package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
    void highlightTextWrapsEveryMatchAndEscapesTheRest() {
        assertThat(evalModule("markup.js", "m.highlightText('a<b>a', 'a')"))
                .isEqualTo("<mark>a</mark>&lt;b&gt;<mark>a</mark>");
    }

    @Test
    void isSensitiveKeyMatchesCredentialLikeNames() {
        assertThat(evalModule("markup.js", "m.isSensitiveKey('db.password')")).isEqualTo(true);
        assertThat(evalModule("markup.js", "m.isSensitiveKey('api-secret')")).isEqualTo(true);
        assertThat(evalModule("markup.js", "m.isSensitiveKey('apiKey')")).isEqualTo(true);
        assertThat(evalModule("markup.js", "m.isSensitiveKey('auth.token')")).isEqualTo(true);
        assertThat(evalModule("markup.js", "m.isSensitiveKey('credential.store')")).isEqualTo(true);
        assertThat(evalModule("markup.js", "m.isSensitiveKey('server.port')")).isEqualTo(false);
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
    void durationSeverityUsesOneSetOfThresholds() {
        assertThat(evalModule("severity.js", "m.durationSeverity(50)")).isEqualTo("");
        assertThat(evalModule("severity.js", "m.durationSeverity(101)")).isEqualTo("slow");
        assertThat(evalModule("severity.js", "m.durationSeverity(501)")).isEqualTo("very-slow");
        assertThat(evalModule("severity.js", "m.SLOW_MS")).isEqualTo(100);
        assertThat(evalModule("severity.js", "m.VERY_SLOW_MS")).isEqualTo(500);
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
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('HTTP_REQUEST') === '\\u{1F310}'")).isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('SCHEDULED_JOB') === '\\u{1F551}'")).isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('MESSAGE_CONSUMER') === '\\u{1F4E9}'")).isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('RPC_CALL') === '\\u{1F517}'")).isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('DATABASE') === '\\u{1F5C2}'")).isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('INTERNAL') === '⚙'")).isEqualTo(true);
        assertThat(evalModule("root-actions.js", "m.rootActionIcon('UNKNOWN') === '❓'")).isEqualTo(true);
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
}
