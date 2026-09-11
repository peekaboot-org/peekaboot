package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;

class ThemeTokenIT extends PlaywrightTestBase {

    /**
     * Without color-scheme the UA paints scrollbars, the <select> popup, checkboxes and
     * the text caret with its light palette on top of the dark page.
     */
    @Test
    void nativeWidgetsFollowTheTheme() {
        setStoredTheme("dark");
        openDashboard();
        assertThat(cssVar(":root", "color-scheme")).isEqualTo("dark");

        setStoredTheme("light");
        openDashboard();
        assertThat(cssVar(":root", "color-scheme")).isEqualTo("light");
    }

    /**
     * Serves a tokens.css with four declarations stripped - the shape a stale cached copy,
     * predating those tokens, would take; a blocked or 404 load would lose the whole
     * palette, not four tokens. Every var() reading one of the missing tokens still has the
     * light-theme literal as a fallback, so the loss costs the dark palette for that rule
     * and nothing more - a search highlight without ink, an error banner without its wash,
     * or a hover wash without its ink, would be unreadable rather than merely un-themed.
     */
    @Test
    void aTokensFileMissingTheHighlightTintAndWashTokensStillPaintsTheirRules() {
        page.route("**/peekaboot/ui/assets/tokens.css", route -> {
            APIResponse response = route.fetch();
            String withoutTokens =
                    response.text().replaceAll("(?m)^\\s+--pk-(mark-bg|on-mark|danger-tint|on-primary-wash):.*$", "");
            route.fulfill(new Route.FulfillOptions().setContentType("text/css").setBody(withoutTokens));
        });

        openDashboard();
        page.evaluate("() => document.body.insertAdjacentHTML('beforeend', '<mark id=\"pk-mark-probe\">x</mark>')");
        page.addStyleTag(new Page.AddStyleTagOptions().setUrl("/peekaboot/ui/trace-detail/trace-detail.css"));
        page.evaluate("() => document.body.insertAdjacentHTML('beforeend',"
                + " '<span id=\"pk-wash-probe\" class=\"pk-logs-filter-span-clear\">x</span>')");
        page.hover("#pk-wash-probe");

        assertThat(computedStyle("#pk-mark-probe", "backgroundColor"))
                .as("the search highlight keeps its fill")
                .isEqualTo("rgb(254, 240, 138)");
        assertThat(computedStyle("#pk-mark-probe", "color"))
                .as("the search highlight keeps its ink")
                .isEqualTo("rgb(17, 24, 39)");
        assertThat(computedStyle("#error", "backgroundColor"))
                .as("the error banner keeps its wash")
                .isEqualTo("rgba(210, 31, 31, 0.04)");
        assertThat(computedStyle("#pk-wash-probe", "backgroundColor"))
                .as("the primary-fill hover wash keeps its ink")
                .isEqualTo("rgba(13, 17, 23, 0.15)");
    }

    private String computedStyle(String selector, String property) {
        return (String) page.locator(selector).first().evaluate("(el, prop) => getComputedStyle(el)[prop]", property);
    }
}
