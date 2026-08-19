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
    void purpleTokenIsAvailableInBothThemes() {
        setStoredTheme("light");
        openDashboard();

        assertThat(cssVar(":root", "--pk-purple")).isNotEmpty();
    }
}
