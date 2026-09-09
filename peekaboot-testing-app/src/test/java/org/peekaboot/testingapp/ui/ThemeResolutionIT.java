package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.ColorScheme;
import org.junit.jupiter.api.Test;

class ThemeResolutionIT extends PlaywrightTestBase {

    private Object evalTheme(String expression) {
        return importModule("shared/theme.js", expression);
    }

    @Test
    void storedPreferenceWins() {
        setStoredTheme("dark");
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    @Test
    void osPreferenceIsTheFallback() {
        emulateOsColorScheme(ColorScheme.DARK);
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    @Test
    void corruptedStoredValueFallsBackToOsPreference() {
        setStoredTheme("purple");
        emulateOsColorScheme(ColorScheme.DARK);
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("dark");
    }

    @Test
    void applyThemeSetsTheAttributeOnAnyTarget() {
        Object result = evalTheme("""
            (() => {
                const host = document.createElement('div');
                document.body.appendChild(host);
                m.applyTheme(host, 'dark');
                return host.getAttribute('data-theme');
            })()
            """);
        assertThat(result).isEqualTo("dark");
    }

    /**
     * The toolbar and overlay run inside pages Peekaboot does not own, where storage access
     * can throw (private browsing, sandboxed iframes, embedder policy). resolveTheme() must
     * degrade to the OS preference rather than propagate the exception into the host page.
     */
    @Test
    void resolveThemeDegradesToOsPreferenceWhenLocalStorageThrows() {
        page.addInitScript("localStorage.getItem = () => { throw new Error('storage blocked'); };");
        emulateOsColorScheme(ColorScheme.DARK);
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
        emulateOsColorScheme(ColorScheme.LIGHT);
        assertThat(evalTheme("m.resolveTheme()")).isEqualTo("light");
    }

    @Test
    void storeThemePersistsUnderTheSharedKey() {
        Object stored = evalTheme("""
            (() => {
                m.storeTheme('dark');
                return localStorage.getItem('peekaboot-theme');
            })()
            """);
        assertThat(stored).isEqualTo("dark");
    }

    /**
     * watchTheme must return a callable unsubscribe function that actually detaches the
     * listeners, not just something callable that does nothing.
     */
    @Test
    void watchThemeUnsubscribeStopsFurtherStorageNotifications() {
        Object callCountAfterUnsubscribe = evalTheme("""
            (() => {
                let calls = 0;
                const unsubscribe = m.watchTheme(() => { calls++; });
                unsubscribe();
                window.dispatchEvent(new StorageEvent('storage', {key: 'peekaboot-theme', newValue: 'dark'}));
                return calls;
            })()
            """);
        assertThat(callCountAfterUnsubscribe).isEqualTo(0);
    }

    @Test
    void watchThemeInvokesCallbackOnMatchingStorageEvent() {
        Object result = evalTheme("""
            (() => {
                localStorage.setItem('peekaboot-theme', 'dark');
                let received = null;
                m.watchTheme((theme) => { received = theme; });
                window.dispatchEvent(new StorageEvent('storage', {key: 'peekaboot-theme', newValue: 'dark'}));
                return received;
            })()
            """);
        assertThat(result).isEqualTo("dark");
    }

    @Test
    void watchThemeIgnoresStorageEventsForUnrelatedKeys() {
        Object result = evalTheme("""
            (() => {
                let calls = 0;
                m.watchTheme(() => { calls++; });
                window.dispatchEvent(new StorageEvent('storage', {key: 'some-other-key', newValue: 'x'}));
                return calls;
            })()
            """);
        assertThat(result).isEqualTo(0);
    }

    /**
     * A dark-theme reader would otherwise see the light palette painted first on every
     * load: the module script that resolves the theme is deferred past first paint.
     * assets/theme-boot.js, linked from index.html's head, stamps data-theme before the
     * stylesheets apply. main.js is refused here (a real network failure), so whatever
     * the attribute says was set by that script alone.
     */
    @Test
    void storedThemeIsStampedBeforeTheModuleScriptRuns() {
        setStoredTheme("dark");
        emulateOsColorScheme(ColorScheme.LIGHT);
        page.route("**/dashboard/main.js", route -> route.abort());

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");

        assertThat(page.getAttribute("html", "data-theme")).isEqualTo("dark");
    }

    @Test
    void osPreferenceIsStampedBeforeTheModuleScriptRunsWhenNothingIsStored() {
        emulateOsColorScheme(ColorScheme.DARK);
        page.route("**/dashboard/main.js", route -> route.abort());

        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");

        assertThat(page.getAttribute("html", "data-theme")).isEqualTo("dark");
    }

    /**
     * A host that puts script-src 'self' in front of /peekaboot/** drops every inline
     * script, so the pre-paint resolution has to arrive as a file the policy allows -
     * otherwise the light flash comes back for exactly the readers who hardened their
     * deployment. The policy is applied by adding the header to the real dashboard
     * response; asserting it round-tripped keeps the test from passing with no policy in
     * force at all.
     */
    @Test
    void theThemeIsStampedBeforeTheModuleScriptRunsUnderAScriptSrcCsp() {
        setStoredTheme("dark");
        emulateOsColorScheme(ColorScheme.LIGHT);
        page.route("**/dashboard/main.js", route -> route.abort());
        serveWithCsp("**/peekaboot/ui/dashboard/index.html", "script-src 'self'");

        Response navigation = page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");

        assertThat(navigation.headers())
                .as("the policy reached the document under test")
                .containsEntry("content-security-policy", "script-src 'self'");
        assertThat(page.getAttribute("html", "data-theme")).isEqualTo("dark");
    }
}
