package org.peekaboot.testingapp.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the shared component builders and API client in a real browser.
 *
 * kvRow/group intentionally do NOT accept pre-escaped markup (no keyHtml/valueHtml/
 * nameHtml). They accept a plain `highlight` query string and call highlightText()
 * themselves, so no caller can smuggle raw HTML into a row built from backend-supplied
 * property keys/values.
 */
class ComponentBuilderTest extends PlaywrightTestBase {

    private Object evalBuilders(String body) {
        if (!page.url().equals(baseUrl + "/peekaboot/ui/pk-blank.html")) {
            page.navigate(baseUrl + "/peekaboot/ui/pk-blank.html");
        }
        return page.evaluate(
                "async (body) => { const m = await import('/peekaboot/ui/shared/components.js');"
              + " return await eval('(async () => {' + body + '})()'); }", body);
    }

    @Test
    void badgeCarriesItsVariantClass() {
        assertThat(evalBuilders("return m.badge('UP', 'ok').className;"))
                .isEqualTo("pk-badge pk-badge--ok");
    }

    @Test
    void badgeUsesTextContentSoMarkupCannotInject() {
        assertThat(evalBuilders("return m.badge('<b>x</b>', 'ok').innerHTML;"))
                .isEqualTo("&lt;b&gt;x&lt;/b&gt;");
    }

    @Test
    void kvRowMarksSensitiveValues() {
        assertThat(evalBuilders(
                "return m.kvRow('password', '***', {sensitive: true})"
              + ".querySelector('.pk-kv__value').className;"))
                .isEqualTo("pk-kv__value pk-kv__value--sensitive");
    }

    @Test
    void kvRowAppliesMonoAndTightModifiers() {
        assertThat(evalBuilders(
                "return m.kvRow('k', 'v', {mono: true, tight: true}).className;"))
                .isEqualTo("pk-kv pk-kv--tight");
        assertThat(evalBuilders(
                "return m.kvRow('k', 'v', {mono: true}).querySelector('.pk-kv__value').className;"))
                .isEqualTo("pk-kv__value pk-kv__value--mono");
    }

    @Test
    void kvRowWithoutHighlightUsesPlainTextForKeyAndValue() {
        assertThat(evalBuilders(
                "return m.kvRow('k', null).querySelector('.pk-kv__value').textContent;"))
                .isEqualTo("-");
        assertThat(evalBuilders(
                "return m.kvRow('k', 'v').querySelector('.pk-kv__value').innerHTML;"))
                .isEqualTo("v");
    }

    @Test
    void kvRowHighlightWrapsMatchesInBothKeyAndValue() {
        assertThat(evalBuilders(
                "return m.kvRow('secret-key', 'sec-ret', {highlight: 'ret'})"
              + ".querySelector('.pk-kv__key').innerHTML;"))
                .isEqualTo("sec<mark>ret</mark>-key");
        assertThat(evalBuilders(
                "return m.kvRow('secret-key', 'sec-ret', {highlight: 'ret'})"
              + ".querySelector('.pk-kv__value').innerHTML;"))
                .isEqualTo("sec-<mark>ret</mark>");
    }

    /**
     * The mandated contract change: highlight is a plain query string, never markup.
     * A broken implementation that assigned a caller-supplied keyHtml/valueHtml via
     * innerHTML would let this string create a real <img> element; this must never
     * happen no matter what the key/value/highlight arguments contain.
     */
    @Test
    void kvRowHighlightCannotBeUsedToInjectMarkup() {
        assertThat(evalBuilders("""
                const row = m.kvRow('<img src=x onerror=alert(1)>', 'v', {highlight: 'img'});
                document.body.appendChild(row);
                const hasImg = row.querySelector('img') !== null;
                const hasMark = row.querySelector('mark') !== null;
                const keyText = row.querySelector('.pk-kv__key').textContent;
                return hasImg + '|' + hasMark + '|' + keyText;
                """))
                .isEqualTo("false|true|<img src=x onerror=alert(1)>");
    }

    @Test
    void groupStartsCollapsedByDefault() {
        assertThat(evalBuilders(
                "const g = m.group({name: 'n', count: '1 item'});"
              + " return g.header.getAttribute('aria-expanded');"))
                .isEqualTo("false");
    }

    @Test
    void groupHeaderTogglesBothAriaAndVisibility() {
        assertThat(evalBuilders("""
                const g = m.group({name: 'n', count: '1 item'});
                document.body.appendChild(g.element);
                g.header.click();
                const afterOpen = g.header.getAttribute('aria-expanded') + ':' + g.list.hasAttribute('hidden');
                g.header.click();
                const afterClose = g.header.getAttribute('aria-expanded') + ':' + g.list.hasAttribute('hidden');
                return afterOpen + '|' + afterClose;
                """))
                .isEqualTo("true:false|false:true");
    }

    @Test
    void headerIsWiredToItsListByAria() {
        assertThat(evalBuilders("""
                const g = m.group({name: 'n', count: '1 item'});
                document.body.appendChild(g.element);
                return g.header.getAttribute('aria-controls') === g.list.id;
                """))
                .isEqualTo(true);
    }

    /** Same escaping guarantee as kvRow, applied to the group's name. */
    @Test
    void groupNameHighlightCannotBeUsedToInjectMarkup() {
        assertThat(evalBuilders("""
                const g = m.group({name: '<img src=x onerror=alert(1)>', count: '1', highlight: 'img'});
                document.body.appendChild(g.element);
                const hasImg = g.element.querySelector('img') !== null;
                const hasMark = g.element.querySelector('mark') !== null;
                return hasImg + '|' + hasMark;
                """))
                .isEqualTo("false|true");
    }

    @Test
    void meterClampsAndClassifies() {
        assertThat(evalBuilders("return m.meter(150).querySelector('.pk-meter__fill').style.width;"))
                .isEqualTo("100%");
        assertThat(evalBuilders("return m.meter(-10).querySelector('.pk-meter__fill').style.width;"))
                .isEqualTo("0%");

        assertThat(evalBuilders("return m.meter(69).querySelector('.pk-meter__fill').className;"))
                .isEqualTo("pk-meter__fill");
        assertThat(evalBuilders("return m.meter(70).querySelector('.pk-meter__fill').className;"))
                .isEqualTo("pk-meter__fill pk-meter__fill--warning");
        assertThat(evalBuilders("return m.meter(89).querySelector('.pk-meter__fill').className;"))
                .isEqualTo("pk-meter__fill pk-meter__fill--warning");
        assertThat(evalBuilders("return m.meter(90).querySelector('.pk-meter__fill').className;"))
                .isEqualTo("pk-meter__fill pk-meter__fill--danger");
    }

    /**
     * groupList + expandedKeys exist so a 30s auto-refresh does not silently collapse
     * a group the user just opened: expandedKeys() must be read before the container
     * is cleared, and the next groupList() call must restore it. A groupList that
     * ignores the restored keys entirely, or an expandedKeys that fails to capture
     * the click, would both leave group "a" collapsed after the re-render below.
     */
    @Test
    void groupListRestoresExpandedKeysAcrossRerender() {
        assertThat(evalBuilders("""
                const container = document.createElement('div');
                document.body.appendChild(container);
                const groups = [{id: 'a', name: 'Alpha'}, {id: 'b', name: 'Beta'}];
                const render = (expanded) => m.groupList(container, groups, {
                    key: g => g.id,
                    header: g => ({name: g.name, count: '1 item'}),
                    items: (g, list) => { list.textContent = g.name; },
                    expandedKeys: expanded
                });

                render();
                container.querySelector('[data-group-key="a"] .pk-group__header').click();
                const captured = m.expandedKeys(container);

                container.innerHTML = '';
                render(captured);

                const headerA = container.querySelector('[data-group-key="a"] .pk-group__header');
                const headerB = container.querySelector('[data-group-key="b"] .pk-group__header');
                return headerA.getAttribute('aria-expanded') + ':' + headerB.getAttribute('aria-expanded');
                """))
                .isEqualTo("true:false");
    }

    @Test
    void expandedKeysIsEmptyWhenNothingIsExpanded() {
        assertThat(evalBuilders("""
                const container = document.createElement('div');
                document.body.appendChild(container);
                m.groupList(container, [{id: 'a', name: 'Alpha'}], {
                    key: g => g.id,
                    header: g => ({name: g.name, count: '1 item'}),
                    items: () => {}
                });
                return m.expandedKeys(container).size;
                """))
                .isEqualTo(0);
    }

    @Test
    void apiClientReachesTheRealEndpoint() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object tracing = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/api.js');
                const client = m.createClient({basePath: '/peekaboot'});
                const features = await client.get('/api/features');
                return features.tracing;
            }
            """);
        assertThat(tracing).isEqualTo(true);
    }

    @Test
    void apiClientRejectsWithTheStatus() {
        page.navigate(baseUrl + "/peekaboot/ui/dashboard/index.html");
        Object message = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/api.js');
                const client = m.createClient({basePath: '/peekaboot'});
                try { await client.get('/api/traces/does-not-exist/insights'); return 'resolved'; }
                catch (e) { return e.message; }
            }
            """);
        assertThat((String) message).startsWith("HTTP ");
    }

    /**
     * Fires two calls to the same path without awaiting between them, so both are
     * genuinely in flight together (the dashboard's auto-refresh, manual refresh and
     * locale switch all do this). The first request's underlying network response is
     * held back so it arrives strictly after the second's -- the exact "slow older
     * response" scenario the per-path generation guard exists for. If the guard were
     * removed (or checked before the await instead of after), the first call would
     * resolve with the real feature payload instead of null.
     */
    @Test
    void staleOverlappingResponseIsSupersededByTheNewerCall() {
        openDashboard();

        AtomicInteger callCount = new AtomicInteger(0);
        page.route("**/peekaboot/api/features", route -> {
            if (callCount.getAndIncrement() == 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            route.resume();
        });

        Object result = page.evaluate("""
            async () => {
                const m = await import('/peekaboot/ui/shared/api.js');
                const client = m.createClient({basePath: '/peekaboot'});
                const first = client.get('/api/features');
                const second = client.get('/api/features');
                const [firstResult, secondResult] = await Promise.all([first, second]);
                return (firstResult === null) + '|' + (secondResult !== null && secondResult.tracing === true);
            }
            """);

        assertThat(result).isEqualTo("true|true");
    }
}
