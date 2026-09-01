package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.peekaboot.example.security.PeekabootSecurityConfig;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * What {@link PeekabootSecurityConfig} looks like from a real browser, which is where its
 * one non-obvious consequence lives: the dev toolbar is rendered into the application's own
 * pages by DevToolbarFilter, but the module that fills it lives at
 * {@code /peekaboot/ui/toolbar/toolbar.js} - a path the chain gates. So a reader outside the
 * role gets the bar with the sign-in notice on it rather than the request's numbers, and
 * these tests pin both halves of that.
 *
 * <p>{@code SecuredPeekabootIT} covers the HTTP contract itself; this covers
 * only what a browser additionally shows.
 */
@SpringBootTest(
        classes = {TestingApp.class, PeekabootSecurityConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// inheritProfiles = false for the same reason as ScreenshotCapture: PlaywrightTestBase
// carries @ActiveProfiles("test"), and that profile is the one that excludes the servlet
// security auto-configuration this whole class exists to exercise.
@ActiveProfiles(profiles = "security", inheritProfiles = false)
class SecuredDashboardIT extends PlaywrightTestBase {

    private static final String TOOLBAR_SCRIPT = "/peekaboot/ui/toolbar/toolbar.js";

    /** The credentials PeekabootSecurityConfig's in-memory store holds for ROLE_ADMIN. */
    @Override
    protected Page browserContextPage() {
        return browser()
                .newContext(newContextOptions().setHttpCredentials("admin", "admin-password"))
                .newPage();
    }

    @Test
    void theDashboardRendersForAnAdmin() {
        openDashboard();

        assertThat(page.textContent("h1")).isEqualTo("peekaboot");
        assertThat(page.textContent("#build-info")).contains("peekaboot-testing-app");
    }

    @Test
    void theToolbarResolvesItsMetricsForAnAdmin() {
        openPersonsPage();

        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-metrics').querySelector('.pk-stat') !== null");

        String metrics = (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.querySelector('#pk-metrics').textContent");
        assertThat(metrics).doesNotContain("?");
    }

    @Test
    void anAnonymousBrowserIsRefusedTheDashboard() {
        try (BrowserContext anonymous = browser().newContext(newContextOptions())) {
            Page anonymousPage = anonymous.newPage();

            Response response = anonymousPage.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");

            assertThat(response.status()).isEqualTo(401);
            assertThat(anonymousPage.locator("#overview-tab").count()).isZero();
        }
    }

    /**
     * The case the server-rendered shell exists for. The toolbar's module is a
     * {@code /peekaboot/**} request like any other, so it is refused - were the bar itself
     * script-rendered, that would mean no bar at all, on every page of the application, with
     * nothing to tell the reader why. Rendered by DevToolbarFilter, the bar arrives with the
     * page and says so.
     */
    @Test
    void theToolbarExplainsItselfToAnAnonymousBrowser() {
        try (BrowserContext anonymous = browser().newContext(newContextOptions())) {
            Page anonymousPage = anonymous.newPage();
            List<Integer> toolbarScriptStatuses = new ArrayList<>();
            anonymousPage.onResponse(response -> {
                if (response.url().endsWith(TOOLBAR_SCRIPT)) {
                    toolbarScriptStatuses.add(response.status());
                }
            });

            Response response = anonymousPage.navigate(baseUrl + "/persons");
            anonymousPage.waitForLoadState(LoadState.NETWORKIDLE);

            assertThat(response.status()).isEqualTo(200);
            assertThat(anonymousPage.textContent("h1")).isEqualTo("Persons");
            assertThat(toolbarScriptStatuses)
                    .as("the toolbar module is a /peekaboot/** request like any other")
                    .containsExactly(401);
            assertThat(anonymousPage.locator("#peekaboot-toolbar-host").count())
                    .as("the bar is rendered by the filter, so it survives the refused script")
                    .isEqualTo(1);
            assertThat(authNoticeText(anonymousPage)).isEqualTo("Sign in to see this request");
        }
    }

    /**
     * The notice is a real link rather than something a click handler opens, because in the
     * case it exists for no handler was ever bound - the script that binds them was refused.
     */
    @Test
    void theNoticeLinksAnAnonymousBrowserToTheDashboard() {
        try (BrowserContext anonymous = browser().newContext(newContextOptions())) {
            Page anonymousPage = anonymous.newPage();
            anonymousPage.navigate(baseUrl + "/persons");
            anonymousPage.waitForLoadState(LoadState.NETWORKIDLE);

            String href = (String) anonymousPage.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                    + ".shadowRoot.querySelector('#pk-auth a').getAttribute('href')");

            assertThat(href).isEqualTo("/peekaboot/");
        }
    }

    /**
     * The notice is revealed by a delayed animation rather than by a script, so that a page
     * blocking inline script still gets it. This pins the reveal actually happening - an
     * always-transparent notice would satisfy every other assertion here.
     */
    @Test
    void theNoticeBecomesVisibleForAnAnonymousBrowser() {
        try (BrowserContext anonymous = browser().newContext(newContextOptions())) {
            Page anonymousPage = anonymous.newPage();
            anonymousPage.navigate(baseUrl + "/persons");

            anonymousPage.waitForFunction("() => getComputedStyle("
                    + "document.getElementById('peekaboot-toolbar-host').shadowRoot"
                    + ".getElementById('pk-auth')).opacity === '1'");
        }
    }

    /**
     * The complement: reaching the script at all proves the reader may read Peekaboot's data,
     * so the notice is removed outright. Removed rather than hidden, so it leaves the
     * accessibility tree too.
     */
    @Test
    void theNoticeIsGoneOnceTheScriptHasRunForAnAdmin() {
        openPersonsPage();

        page.waitForFunction("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.getElementById('pk-auth') === null");
    }

    private static String authNoticeText(Page page) {
        return (String) page.evaluate("() => document.getElementById('peekaboot-toolbar-host')"
                + ".shadowRoot.getElementById('pk-auth').textContent.trim()");
    }
}
