package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

class ThemeTokenIT extends PlaywrightTestBase {

    /**
     * Each theme is opened against the opposite OS preference, so the same test also
     * proves the stored preference beats prefers-color-scheme on the rendered tokens -
     * headless Chromium defaults to light, where "stored light" would pass regardless.
     */
    @Test
    void storedLightAndDarkPreferencesResolveDifferentSurfaceTokens() {
        setStoredTheme("light");
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.DARK));
        openDashboard();
        String lightBackground = cssVar(":root", "--pk-bg");
        String lightText = cssVar(":root", "--pk-text");

        setStoredTheme("dark");
        page.emulateMedia(new Page.EmulateMediaOptions().setColorScheme(ColorScheme.LIGHT));
        openDashboard();

        assertThat(cssVar(":root", "--pk-bg")).isNotEqualTo(lightBackground);
        assertThat(cssVar(":root", "--pk-text")).isNotEqualTo(lightText);
    }

    /**
     * The light theme's status colours are tuned for contrast against white: #d21f1f on
     * the #0d1117 dark background scores 3.57:1, below WCAG AA's 4.5:1. The dark overrides
     * (#f85149 scores 5.65:1) keep the dashboard's status colours readable and aligned
     * with the overlay's palette.
     */
    @Test
    void darkThemeStatusColoursAreDarkAdapted() {
        setStoredTheme("dark");
        openDashboard();

        assertThat(cssVar(":root", "--pk-danger")).isEqualTo("#f85149");
        assertThat(cssVar(":root", "--pk-success")).isEqualTo("#3fb950");
        assertThat(cssVar(":root", "--pk-warning")).isEqualTo("#d29922");
    }

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
     * tokens.css is the documented override point, so a consuming application may shadow
     * it with a copy of its own that predates a token. Every rule reading one carries the
     * light-theme literal as its var() fallback, so such a copy costs the dark palette for
     * that rule and nothing more - a search highlight without ink, or an error banner
     * without its wash, would be unreadable rather than merely un-themed.
     */
    @Test
    void aTokensFileMissingTheHighlightAndTintTokensStillPaintsTheirRules() {
        page.route("**/peekaboot/ui/assets/tokens.css", route -> {
            APIResponse response = route.fetch();
            String withoutTokens = response.text().replaceAll("(?m)^\\s+--pk-(mark-bg|on-mark|danger-tint):.*$", "");
            route.fulfill(new Route.FulfillOptions().setContentType("text/css").setBody(withoutTokens));
        });

        openDashboard();
        page.evaluate("() => document.body.insertAdjacentHTML('beforeend', '<mark id=\"pk-mark-probe\">x</mark>')");

        assertThat(computedStyle("#pk-mark-probe", "backgroundColor"))
                .as("the search highlight keeps its fill")
                .isEqualTo("rgb(254, 240, 138)");
        assertThat(computedStyle("#pk-mark-probe", "color"))
                .as("the search highlight keeps its ink")
                .isEqualTo("rgb(17, 24, 39)");
        assertThat(computedStyle("#error", "backgroundColor"))
                .as("the error banner keeps its wash")
                .isEqualTo("rgba(210, 31, 31, 0.04)");
    }

    private String computedStyle(String selector, String property) {
        return (String) page.locator(selector).first().evaluate("(el, prop) => getComputedStyle(el)[prop]", property);
    }
}
