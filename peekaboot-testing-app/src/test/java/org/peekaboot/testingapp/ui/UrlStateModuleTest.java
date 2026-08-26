package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Exercises shared/url-state.js in a real browser, imported from the running app - the
 * same module-import-into-blank-page pattern as SharedModuleTest.
 */
class UrlStateModuleTest extends PlaywrightTestBase {

    private Object evalModule(String expression) {
        if (!page.url().equals(baseUrl + "/peekaboot/ui/pk-blank.html")) {
            page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");
        }
        return page.evaluate(
                "async ([mod, expr]) => { const m = await import(mod); return eval(expr); }",
                java.util.List.of("/peekaboot/ui/shared/url-state.js", expression));
    }

    @Test
    void parseAppHashKeepsLegacyTwoSegmentLinksWorking() {
        assertThat(evalModule("JSON.stringify(m.parseAppHash('#traces/deadbeef'))"))
                .isEqualTo("{\"tab\":\"traces\",\"detail\":\"deadbeef\",\"subview\":null,\"params\":{}}");
        assertThat(evalModule("JSON.stringify(m.parseAppHash('#meters'))"))
                .isEqualTo("{\"tab\":\"meters\",\"detail\":null,\"subview\":null,\"params\":{}}");
        assertThat(evalModule("JSON.stringify(m.parseAppHash(''))"))
                .isEqualTo("{\"tab\":\"overview\",\"detail\":null,\"subview\":null,\"params\":{}}");
        assertThat(evalModule("JSON.stringify(m.parseAppHash('#'))"))
                .isEqualTo("{\"tab\":\"overview\",\"detail\":null,\"subview\":null,\"params\":{}}");
    }

    @Test
    void parseAndBuildRoundTripThreeSegmentsAndQuery() {
        assertThat(evalModule("JSON.stringify(m.parseAppHash(m.buildAppHash("
                        + "m.parseAppHash('#traces/abc/logs?span=c257a660&level=WARN&q=a b'))))"))
                .isEqualTo("{\"tab\":\"traces\",\"detail\":\"abc\",\"subview\":\"logs\","
                        + "\"params\":{\"span\":\"c257a660\",\"level\":\"WARN\",\"q\":\"a b\"}}");

        assertThat(evalModule("JSON.stringify(m.parseAppHash(m.buildAppHash("
                        + "m.parseAppHash('#traces?type=A,B&op=GET /persons'))))"))
                .isEqualTo("{\"tab\":\"traces\",\"detail\":null,\"subview\":null,"
                        + "\"params\":{\"type\":\"A,B\",\"op\":\"GET /persons\"}}");
    }

    @Test
    void buildAppHashOmitsEmptySegmentsAndParams() {
        assertThat(evalModule("m.buildAppHash({tab: 'traces'})")).isEqualTo("#traces");
        assertThat(evalModule("m.buildAppHash({tab: 'traces', detail: 'abc', params: {q: '', level: 'WARN'}})"))
                .isEqualTo("#traces/abc?level=WARN");
        // A subview is only ever emitted alongside a detail segment.
        assertThat(evalModule("m.buildAppHash({tab: 'traces', subview: 'logs'})"))
                .isEqualTo("#traces");
    }
}
