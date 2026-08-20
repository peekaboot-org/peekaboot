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
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
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
    void formatDurationScalesByMagnitude() {
        assertThat(evalModule("format.js", "m.formatDurationMs(0.4)")).isEqualTo("<1ms");
        assertThat(evalModule("format.js", "m.formatDurationMs(250)")).isEqualTo("250ms");
        assertThat(evalModule("format.js", "m.formatDurationMs(1500)")).isEqualTo("1.50s");
        assertThat(evalModule("format.js", "m.formatDurationMs(90000)")).isEqualTo("1.50m");
        assertThat(evalModule("format.js", "m.formatDurationMs(null)")).isEqualTo("-");
    }

    @Test
    void formatBytesScalesByMagnitude() {
        assertThat(evalModule("format.js", "m.formatBytes(0)")).isEqualTo("0 B");
        assertThat(evalModule("format.js", "m.formatBytes(1536)")).isEqualTo("1.50 KB");
        assertThat(evalModule("format.js", "m.formatBytes(-1)")).isEqualTo("-");
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
}
