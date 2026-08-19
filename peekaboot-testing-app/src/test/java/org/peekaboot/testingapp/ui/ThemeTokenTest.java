package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeTokenTest extends PlaywrightTestBase {

    @Test
    void lightThemeResolvesLightBackground() {
        setStoredTheme("light");
        openDashboard();

        assertThat(cssVar(":root", "--pk-bg")).isEqualTo("#ffffff");
        assertThat(cssVar(":root", "--pk-text")).isEqualTo("#1f2937");
    }

    @Test
    void darkThemeResolvesDarkBackground() {
        setStoredTheme("dark");
        openDashboard();

        assertThat(cssVar(":root", "--pk-bg")).isEqualTo("#0d1117");
        assertThat(cssVar(":root", "--pk-text")).isEqualTo("#c9d1d9");
    }

    @Test
    void purpleTokenResolvesInLightTheme() {
        setStoredTheme("light");
        openDashboard();

        assertThat(cssVar(":root", "--pk-purple")).isEqualTo("#7c3aed");
    }

    @Test
    void purpleTokenResolvesInDarkTheme() {
        setStoredTheme("dark");
        openDashboard();

        assertThat(cssVar(":root", "--pk-purple")).isEqualTo("#a371f7");
    }

    /**
     * Dark mode previously inherited the light theme's status colours, which are tuned
     * for contrast against white: #dc2626 on the #0d1117 background scores 3.92:1,
     * below WCAG AA's 4.5:1. The dark overrides fix that (#f85149 scores 5.65:1) and
     * align the dashboard with the overlay's palette.
     */
    @Test
    void darkThemeStatusColoursAreDarkAdapted() {
        setStoredTheme("dark");
        openDashboard();

        assertThat(cssVar(":root", "--pk-danger")).isEqualTo("#f85149");
        assertThat(cssVar(":root", "--pk-success")).isEqualTo("#3fb950");
        assertThat(cssVar(":root", "--pk-warning")).isEqualTo("#d29922");
    }
}
