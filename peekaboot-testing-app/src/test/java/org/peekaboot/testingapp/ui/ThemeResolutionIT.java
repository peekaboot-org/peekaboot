package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThemeResolutionIT extends PlaywrightTestBase {

    private Object evalTheme(String expression) {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        return page.evaluate(
                "async (expr) => { const m = await import('/peekaboot/ui/shared/theme.js'); return eval(expr); }",
                expression);
    }

    @Test
    void storedPreferenceWins() {
        setStoredTheme("dark");
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    @Test
    void osPreferenceIsTheFallback() {
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions()
                .setColorScheme(com.microsoft.playwright.options.ColorScheme.DARK));
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    @Test
    void corruptedStoredValueFallsBackToOsPreference() {
        setStoredTheme("purple");
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions()
                .setColorScheme(com.microsoft.playwright.options.ColorScheme.DARK));
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    @Test
    void applyThemeSetsTheAttributeOnAnyTarget() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object result = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/theme.js');
                const host = document.createElement('div');
                document.body.appendChild(host);
                m.applyTheme(host, 'dark');
                return host.getAttribute('data-theme');
            }
            """);
        assertThat(result).isEqualTo("dark");
    }

    @Test
    void storageKeyMatchesTheDashboardToggle() {
        assertThat(evalTheme("m.THEME_STORAGE_KEY")).isEqualTo("peekaboot-theme");
    }

    /**
     * The toolbar and overlay run inside pages Peekaboot does not own, where storage access
     * can throw (private browsing, sandboxed iframes, embedder policy). resolveTheme() must
     * degrade to the OS preference rather than propagate the exception into the host page.
     */
    @Test
    void resolveThemeDegradesToOsPreferenceWhenLocalStorageThrows() {
        page.addInitScript("localStorage.getItem = () => { throw new Error('storage blocked'); };");
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions()
                .setColorScheme(com.microsoft.playwright.options.ColorScheme.DARK));
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    /**
     * Same degradation path, but with the OS preference resolving to light, so a broken
     * implementation that swallows the exception and falls back to a hardcoded 'dark'
     * would still be caught.
     */
    @Test
    void resolveThemeDegradesToLightOsPreferenceWhenLocalStorageThrows() {
        page.addInitScript("localStorage.getItem = () => { throw new Error('storage blocked'); };");
        page.emulateMedia(new com.microsoft.playwright.Page.EmulateMediaOptions()
                .setColorScheme(com.microsoft.playwright.options.ColorScheme.LIGHT));
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("light");
    }

    @Test
    void storeThemePersistsUnderTheSharedKey() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object stored = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/theme.js');
                m.storeTheme('dark');
                return localStorage.getItem('peekaboot-theme');
            }
            """);
        assertThat(stored).isEqualTo("dark");
    }

    /**
     * watchTheme must return a callable unsubscribe function that actually detaches the
     * listeners, not just something callable that does nothing.
     */
    @Test
    void watchThemeUnsubscribeStopsFurtherStorageNotifications() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object callCountAfterUnsubscribe = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/theme.js');
                let calls = 0;
                const unsubscribe = m.watchTheme(() => { calls++; });
                unsubscribe();
                window.dispatchEvent(new StorageEvent('storage', {key: 'peekaboot-theme', newValue: 'dark'}));
                return calls;
            }
            """);
        assertThat(callCountAfterUnsubscribe).isEqualTo(0);
    }

    @Test
    void watchThemeInvokesCallbackOnMatchingStorageEvent() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object result = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/theme.js');
                localStorage.setItem('peekaboot-theme', 'dark');
                let received = null;
                m.watchTheme((theme) => { received = theme; });
                window.dispatchEvent(new StorageEvent('storage', {key: 'peekaboot-theme', newValue: 'dark'}));
                return received;
            }
            """);
        assertThat(result).isEqualTo("dark");
    }

    @Test
    void watchThemeIgnoresStorageEventsForUnrelatedKeys() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object result = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/theme.js');
                let calls = 0;
                m.watchTheme(() => { calls++; });
                window.dispatchEvent(new StorageEvent('storage', {key: 'some-other-key', newValue: 'x'}));
                return calls;
            }
            """);
        assertThat(result).isEqualTo(0);
    }
}
