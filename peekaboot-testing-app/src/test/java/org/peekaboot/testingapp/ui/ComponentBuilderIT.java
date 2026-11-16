package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises the shared component builders, the keyed updates under them, the API client and
 * the self-fetching tab shell in a real browser.
 *
 * kvRow/group intentionally do NOT accept pre-escaped markup (no keyHtml/valueHtml/
 * nameHtml). They accept a plain `highlight` query string and mark its matches
 * themselves, so no caller can smuggle raw HTML into a row built from backend-supplied
 * property keys/values.
 */
class ComponentBuilderIT extends PlaywrightTestBase {

    /** Runs {@code body}, a function body over the components module {@code m}, and returns what it returns. */
    private Object evalBuilders(String body) {
        return importModule("shared/components.js", "(async () => {" + body + "})()");
    }

    /** The variant class is what the sheet colours by, so it belongs with the escaping rule. */
    @Test
    void badgeUsesTextContentSoMarkupCannotInject() {
        assertThat(evalBuilders("const b = m.badge('<b>x</b>', 'ok'); return b.className + '|' + b.innerHTML;"))
                .isEqualTo("pk-badge pk-badge--ok|&lt;b&gt;x&lt;/b&gt;");
    }

    @Test
    void kvRowWithoutHighlightUsesPlainTextForKeyAndValue() {
        assertThat(evalBuilders("return m.kvRow('k', null).querySelector('.pk-kv__value').textContent;"))
                .isEqualTo("-");
        assertThat(evalBuilders("""
                const row = m.kvRow('k', 'v', {mono: true, tight: true});
                return row.className + '|' + row.querySelector('.pk-kv__value').className
                    + '|' + row.querySelector('.pk-kv__value').innerHTML;
                """)).isEqualTo("pk-kv pk-kv--tight|pk-kv__value pk-kv__value--mono|v");
    }

    @Test
    void kvRowHighlightWrapsMatchesInBothKeyAndValue() {
        assertThat(evalBuilders("return m.kvRow('secret-key', 'sec-ret', {highlight: 'ret'})"
                        + ".querySelector('.pk-kv__key').innerHTML;"))
                .isEqualTo("sec<mark>ret</mark>-key");
        assertThat(evalBuilders("return m.kvRow('secret-key', 'sec-ret', {highlight: 'ret'})"
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
                """)).isEqualTo("false|true|<img src=x onerror=alert(1)>");
    }

    /** No string is parsed on the way into a row, so markup in a key or a value stays the text it is, marked or not. */
    @Test
    void aValueThatLooksLikeMarkupRendersAsText() {
        assertThat(evalBuilders("""
                const row = m.kvRow('<img src=x onerror=alert(1)>', '<b>v</b>', {highlight: 'img'});
                return [row.querySelector('img, b') === null, row.querySelector('mark').textContent,
                    row.querySelector('.pk-kv__key').textContent, row.querySelector('.pk-kv__value').textContent];
                """)).isEqualTo(List.of(true, "img", "<img src=x onerror=alert(1)>", "<b>v</b>"));
    }

    @Test
    void groupStartsCollapsedByDefault() {
        assertThat(evalBuilders("const g = m.group({name: 'n', count: '1 item'});"
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
                """)).isEqualTo("true:false|false:true");
    }

    /** A real button, not a click-handled div: keyboard activation and the ARIA wiring both. */
    @Test
    void headerIsARealButtonWiredToItsListByAria() {
        assertThat(evalBuilders("""
                const g = m.group({name: 'n', count: '1 item'});
                document.body.appendChild(g.element);
                return g.header.tagName + '|' + (g.header.getAttribute('aria-controls') === g.list.id);
                """)).isEqualTo("BUTTON|true");
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
                """)).isEqualTo("false|true");
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
     * A refresh must not close the group the reader opened, nor take focus off its header, and a
     * group nobody opened stays closed.
     */
    @Test
    void groupListKeepsAGroupOpenAndItsHeaderFocusedAcrossAnUpdate() {
        assertThat(evalGroupList("""
                render([alpha, beta], 'en');
                const header = part('a', 'header');
                const list = part('a', 'list');
                header.click();
                header.focus();

                render([{...alpha, count: '2 items'}, beta], 'en');

                return [part('a', 'header') === header, part('a', 'list') === list,
                    header.getAttribute('aria-expanded'), document.activeElement === header,
                    header.querySelector('.pk-group__count').textContent,
                    part('b', 'header').getAttribute('aria-expanded')].join('|');
                """)).isEqualTo("true|true|true|true|2 items|false");
    }

    @Test
    void groupListDropsAGroupThatLeftTheData() {
        assertThat(evalGroupList("""
                render([alpha, beta], 'en');
                render([alpha], 'en');
                return container.querySelectorAll('[data-group-key="b"]').length
                    + '|' + container.querySelectorAll('.pk-group').length;
                """)).isEqualTo("0|1");
    }

    /** The revision stands for what a row shows besides its data: a language switch redraws equal groups. */
    @Test
    void groupListRefillsAnOpenGroupWhenTheRevisionChanges() {
        assertThat(evalGroupList("""
                render([alpha], 'en');
                part('a', 'header').click();
                render([alpha], 'en');
                const sameRevision = part('a', 'list').textContent;
                render([alpha], 'de');
                return sameRevision + '|' + part('a', 'list').textContent
                    + '|' + part('a', 'header').getAttribute('aria-expanded');
                """)).isEqualTo("Alpha en|Alpha de|true");
    }

    @Test
    void aGroupHeaderCarriesItsMetaBeforeTheCount() {
        assertThat(evalBuilders("""
                const g = m.group({name: 'n', count: '1 item', meta: [m.badge('TIMER', 'info')]});
                const meta = g.header.querySelector('.pk-group__meta');
                return [...meta.children].map(child => child.className).join('|')
                    + '|' + g.header.querySelectorAll('.pk-group__count').length;
                """)).isEqualTo("pk-badge pk-badge--info|pk-group__count|1");
        assertThat(evalBuilders(
                        "return m.group({name: 'n', count: '1 item'}).header.querySelector('.pk-group__meta');"))
                .as("a header without meta has no meta element")
                .isNull();
    }

    @Test
    void cardTableKeepsARowWhoseDataDidNotChange() {
        assertThat(evalCardTable("""
                render([{id: 'a', label: 'A'}], 'en');
                const table = target.querySelector('table');
                const kept = target.querySelector('tbody tr');
                render([{id: 'a', label: 'A'}], 'en');
                return [target.querySelector('table') === table, target.querySelector('tbody tr') === kept,
                    table.className, target.querySelectorAll('tbody tr').length].join('|');
                """)).isEqualTo("true|true|pk-table pk-table--card|1");
    }

    /** A timezone switch changes every date a row shows while the row's data stays equal. */
    @Test
    void cardTableRebuildsARowWhenTheRevisionChanges() {
        assertThat(evalCardTable("""
                render([{id: 'a', label: 'A'}], 'en');
                const table = target.querySelector('table');
                const before = target.querySelector('tbody tr');
                render([{id: 'a', label: 'A'}], 'de');
                return [target.querySelector('table') === table, target.querySelector('tbody tr') === before,
                    target.querySelectorAll('tbody tr').length].join('|');
                """)).isEqualTo("true|false|1");
    }

    @Test
    void keyedChildrenLeavesAnUnchangedChildUntouched() {
        assertThat(evalKeyed("""
                m.keyedChildren(parent, [{id: 'a', label: 'A'}, {id: 'b', label: 'B'}], options);
                const first = parent.children[0];
                m.keyedChildren(parent, [{id: 'a', label: 'A'}, {id: 'b', label: 'B'}], options);
                return (parent.children[0] === first) + '|' + builds;
                """)).isEqualTo("true|2");
    }

    /** Re-inserting a node blurs what is focused inside it, so a row that stays is never moved. */
    @Test
    void keyedChildrenAddsAndRemovesWithoutMovingTheRest() {
        assertThat(evalKeyed("""
                const [a, b, c, x] = ['a', 'b', 'c', 'x'].map(id => ({id, label: id}));
                m.keyedChildren(parent, [a, b, c], options);
                const focused = parent.children[1].querySelector('button');
                focused.focus();
                m.keyedChildren(parent, [x, a, b], options);
                return labels() + '|' + (document.activeElement === focused);
                """)).isEqualTo("x,a,b|true");
    }

    @Test
    void keyedChildrenReplacesAChangedChildAndKeepsFocusInside() {
        assertThat(evalKeyed("""
                m.keyedChildren(parent, [{id: 'a', label: 'A'}, {id: 'b', label: 'B'}], options);
                const before = parent.children[1];
                before.querySelector('button').focus();
                m.keyedChildren(parent, [{id: 'a', label: 'A'}, {id: 'b', label: 'B2'}], options);
                return (parent.children[1] !== before) + '|' + labels() + '|' + document.activeElement.textContent;
                """)).isEqualTo("true|A,B2|B2");
    }

    /** Two rows of one trace, two migrations with one script: the second must not swallow the first. */
    @Test
    void keyedChildrenKeepsItemsWithARepeatedKeyApart() {
        assertThat(evalKeyed("""
                const items = [{id: 'a', label: '1'}, {id: 'a', label: '2'}];
                m.keyedChildren(parent, items, options);
                const nodes = [...parent.children];
                m.keyedChildren(parent, items, options);
                return labels() + '|' + nodes.length
                    + '|' + (parent.children.length === 2 && nodes.every((node, i) => parent.children[i] === node));
                """)).isEqualTo("1,2|2|true");
    }

    /** With {@code update}, a changed child is the same node brought up to date, and an unchanged one is left alone. */
    @Test
    void keyedChildrenHandsAChangedChildToUpdateInPlace() {
        assertThat(evalKeyed("""
                const updated = [];
                const update = (element, item) => {
                    updated.push(item.id);
                    element.querySelector('button').textContent = item.label;
                };
                m.keyedChildren(parent, [{id: 'a', label: 'A'}, {id: 'b', label: 'B'}], {...options, update});
                const before = parent.children[1];
                m.keyedChildren(parent, [{id: 'a', label: 'A'}, {id: 'b', label: 'B2'}], {...options, update});
                return (parent.children[1] === before) + '|' + labels() + '|' + updated.join(',') + '|' + builds;
                """)).isEqualTo("true|A,B2|b|2");
    }

    /** A sort order the data itself changed is followed, and what the list did not build makes way. */
    @Test
    void keyedChildrenFollowsTheItemsOrderAndRemovesWhatItDidNotBuild() {
        assertThat(evalKeyed("""
                parent.append(document.createElement('p'), 'loose text');
                const [a, b, c] = ['a', 'b', 'c'].map(id => ({id, label: id}));
                m.keyedChildren(parent, [a, b, c], options);
                const nodes = [...parent.children];
                m.keyedChildren(parent, [c, a, b], options);
                return labels() + '|' + parent.childNodes.length + '|' + builds
                    + '|' + [2, 0, 1].every((old, i) => parent.children[i] === nodes[old]);
                """)).isEqualTo("c,a,b|3|3|true");
    }

    @Test
    void renderIfChangedSkipsAnUnchangedSignature() {
        assertThat(evalKeyed("""
                let fills = 0;
                const fill = () => { fills++; };
                const filled = [m.renderIfChanged(parent, 'one', fill), m.renderIfChanged(parent, 'one', fill)];
                const afterTwo = fills;
                filled.push(m.renderIfChanged(parent, 'two', fill));
                return afterTwo + '|' + fills + '|' + filled.join(',');
                """)).isEqualTo("1|2|true,false,true");
    }

    /** A refill replaces the control the reader is on, so focus goes to the one that took its place. */
    @Test
    void renderIfChangedMovesFocusToTheSamePositionInTheNewContent() {
        assertThat(evalKeyed("""
                const fill = label => element => element.replaceChildren(build({label: 'first'}), build({label}));
                m.renderIfChanged(parent, 'one', fill('old'));
                parent.children[1].querySelector('button').focus();
                m.renderIfChanged(parent, 'two', fill('new'));
                return document.activeElement.textContent;
                """)).isEqualTo("new");
    }

    @Test
    void apiClientReachesTheRealEndpoint() {
        Object tracing = importModule("shared/api.js", """
            (async () => {
                const client = m.createClient({basePath: '/peekaboot'});
                const features = await client.get('/api/features');
                return features.tracing;
            })()
            """);
        assertThat(tracing).isEqualTo(true);
    }

    @Test
    void apiClientRejectsWithTheStatus() {
        Object message = importModule("shared/api.js", """
            (async () => {
                const client = m.createClient({basePath: '/peekaboot'});
                try { await client.get('/api/traces/does-not-exist'); return 'resolved'; }
                catch (e) { return e.message; }
            })()
            """);
        assertThat((String) message)
                .as("the status is what a caller renders, so it has to survive into the message")
                .isEqualTo("HTTP 404");
    }

    /** The poll a manual refresh overtook can still lose its connection, and its caller has newer data by then. */
    @Test
    void aRequestThatFailsAfterANewerOneStartedResolvesToNull() {
        assertThat(evalClientOverHeldRequests("""
                const first = client.get('/x');
                const second = client.get('/x');
                calls[0].reject(new TypeError('network changed'));
                calls[1].resolve(new Response('{"ok":true}'));
                return JSON.stringify([await first, await second]);
                """)).isEqualTo("[null,{\"ok\":true}]");
    }

    @Test
    void anHttpErrorAfterANewerRequestStartedResolvesToNull() {
        assertThat(evalClientOverHeldRequests("""
                const first = client.get('/x');
                const second = client.get('/x');
                calls[0].resolve(new Response('', {status: 503}));
                calls[1].resolve(new Response('{"ok":true}'));
                return JSON.stringify([await first, await second]);
                """)).isEqualTo("[null,{\"ok\":true}]");
    }

    /**
     * The first body is a stream the test holds open, so the first call is past its status
     * check and waiting on the body when the second one starts.
     */
    @Test
    void aBodyThatFailsAfterANewerRequestStartedResolvesToNull() {
        assertThat(evalClientOverHeldRequests("""
                let held;
                const first = client.get('/x');
                calls[0].resolve(new Response(new ReadableStream({start(controller) { held = controller; }})));
                await settle();
                const second = client.get('/x');
                held.enqueue(new TextEncoder().encode('not json'));
                held.close();
                calls[1].resolve(new Response('{"ok":true}'));
                return JSON.stringify([await first, await second]);
                """)).isEqualTo("[null,{\"ok\":true}]");
    }

    @Test
    void theLatestRequestStillRejectsWhenItFails() {
        assertThat(evalClientOverHeldRequests("""
                const only = client.get('/x');
                calls[0].reject(new TypeError('offline'));
                try { await only; return 'resolved'; }
                catch (e) { return e.message; }
                """)).isEqualTo("offline");
    }

    /**
     * Fires two calls to the same path without awaiting between them, so both are
     * genuinely in flight together (the dashboard's auto-refresh, manual refresh and
     * locale switch all do this). The first request is parked at the route until the
     * second's response has arrived, so the older response lands strictly after the
     * newer one however loaded the machine is -- the exact "slow older response"
     * scenario the per-path generation guard exists for. If the guard were removed (or
     * checked before the await instead of after), the first call would resolve with the
     * real feature payload instead of null.
     */
    @Test
    void staleOverlappingResponseIsSupersededByTheNewerCall() {
        ParkedRequest firstRequest = parkFirst("**/peekaboot/api/features");
        page.onResponse(response -> {
            if (response.url().endsWith("/peekaboot/api/features") && firstRequest.isParked()) {
                firstRequest.resume();
            }
        });

        Object result = importModule("shared/api.js", """
            (async () => {
                const client = m.createClient({basePath: '/peekaboot'});
                const first = client.get('/api/features');
                const second = client.get('/api/features');
                const [firstResult, secondResult] = await Promise.all([first, second]);
                return (firstResult === null) + '|' + (secondResult !== null && secondResult.tracing === true);
            })()
            """);

        assertThat(result).isEqualTo("true|true");
    }

    /** A result the tab cannot render is a failed load, so it gets the error state like one. */
    @Test
    void aRenderThatThrowsReachesTheErrorState() {
        assertThat(evalSelfFetchingTab("""
                const tab = m.selfFetchingTab({fetch: async () => ({}), renderResult: () => { throw new Error('boom'); },
                    renderError: (container, error) => seen.push(error.message)});
                await tab.load(container, {});
                return seen;
                """)).isEqualTo(List.of("boom"));
    }

    @Test
    void anAnswerThatArrivesAfterStopRendersNothing() {
        assertThat(evalSelfFetchingTab("""
                let release;
                const tab = m.selfFetchingTab({fetch: () => new Promise(resolve => { release = resolve; }),
                    renderResult: () => seen.push('result'), renderError: () => seen.push('error')});
                const pending = tab.load(container, {}); tab.stop(); release({}); await pending;
                return seen;
                """)).isEqualTo(List.of());
    }

    @Test
    void aFailureThatArrivesAfterStopRendersNothing() {
        assertThat(evalSelfFetchingTab("""
                let fail;
                const tab = m.selfFetchingTab({fetch: () => new Promise((resolve, reject) => { fail = reject; }),
                    renderResult: () => seen.push('result'), renderError: () => seen.push('error')});
                const pending = tab.load(container, {}); tab.stop(); fail(new Error('offline')); await pending;
                return seen;
                """)).isEqualTo(List.of());
    }

    /** A control the tab wired keeps its listener while the tab is hidden, so its refetch has to wait for the next load. */
    @Test
    void aRefetchAfterStopFetchesNothingUntilTheNextLoad() {
        assertThat(evalSelfFetchingTab("""
                let fetches = 0;
                const tab = m.selfFetchingTab({fetch: async () => ++fetches,
                    renderResult: (container, result) => seen.push(result), renderError: () => seen.push('error')});
                await tab.load(container, {});
                tab.stop();
                await tab.refetch();
                seen.push('stopped');
                await tab.load(container, {});
                await tab.refetch();
                return seen;
                """)).isEqualTo(List.of(1, "stopped", 2, 3));
    }

    @Test
    void tableBuildsAHeaderedTableInsideAScrollBox() {
        assertThat(evalBuilders("""
                const row = document.createElement('tr');
                row.innerHTML = '<td>x</td>';
                const scroll = m.table(['A', 'B'], [row], {className: 'pk-x'});
                const headers = [...scroll.querySelectorAll('th')].map(th => th.scope + ':' + th.textContent).join(',');
                return scroll.className + '|' + scroll.firstElementChild.className + '|' + headers
                    + '|' + scroll.querySelectorAll('tbody tr').length;
                """)).isEqualTo("pk-table-scroll|pk-table pk-x|col:A,col:B|1");
    }

    /** A title only when one is given: an empty title attribute would still hide an ancestor's tooltip. */
    @Test
    void badgeCarriesATitleOnlyWhenOneIsGiven() {
        assertThat(evalBuilders(
                        "return m.badge('x', 'ok', {title: 'why'}).title + '|' + m.badge('x').hasAttribute('title');"))
                .isEqualTo("why|false");
    }

    /** The label is both the tooltip and the accessible name; the glyph is the link's only text. */
    @Test
    void iconLinkNamesItselfByItsLabel() {
        assertThat(evalBuilders("""
                const link = m.iconLink('#traces?op=a', {label: 'View traces', icon: '>', className: 'pk-x'});
                return [link.tagName, link.className, link.getAttribute('href'), link.title,
                    link.getAttribute('aria-label'), link.textContent].join('|');
                """)).isEqualTo("A|pk-icon-link pk-x|#traces?op=a|View traces|View traces|>");
    }

    @Test
    void cellTakesTextAndElementChildrenAndATitleOnlyWhenGiven() {
        assertThat(evalBuilders("""
                const td = m.cell({className: 'pk-x'}, 'a ', m.badge('b'));
                return td.tagName + '|' + td.className + '|' + td.hasAttribute('title') + '|' + td.innerHTML;
                """)).isEqualTo("TD|pk-x|false|a <span class=\"pk-badge pk-badge--muted\">b</span>");
        assertThat(evalBuilders("const td = m.textCell('full text', 'pk-x'); return td.title + '|' + td.textContent;"))
                .isEqualTo("full text|full text");
        assertThat(evalBuilders("const td = m.textCell(null); return td.hasAttribute('title') + '|' + td.textContent;"))
                .isEqualTo("false|");
    }

    /** Every builder writes its text as a text node, so a caller's markup stays literal. */
    @Test
    void buildersUseTextContent() {
        assertThat(evalBuilders("return m.emptyState('<b>').outerHTML;"))
                .isEqualTo("<p class=\"pk-empty\">&lt;b&gt;</p>");
        assertThat(evalBuilders("return m.loadingBlock('Loading x').outerHTML;"))
                .isEqualTo("<div class=\"pk-loading\"><div class=\"pk-spinner\"></div><p>Loading x</p></div>");
    }

    /**
     * Runs {@code body}, a function body over the components module {@code m}, with an empty
     * {@code container} in the document, {@code render(groups, revision)} listing groups shaped
     * {@code {id, name, count}} into it, and {@code part(id, name)} finding one group's
     * {@code header} or {@code list}.
     */
    private Object evalGroupList(String body) {
        return evalBuilders("""
                const container = document.body.appendChild(document.createElement('div'));
                const render = (groups, revision) => m.groupList(container, groups, {
                    key: g => g.id,
                    header: g => ({name: g.name, count: g.count}),
                    items: (g, list) => list.append(`${g.name} ${revision}`),
                    revision
                });
                const part = (id, name) => container.querySelector(`[data-group-key="${id}"] .pk-group__${name}`);
                const alpha = {id: 'a', name: 'Alpha', count: '1 item'};
                const beta = {id: 'b', name: 'Beta', count: '1 item'};
                """ + body);
    }

    /**
     * Runs {@code body}, a function body over the components module {@code m}, with an empty
     * {@code target} in the document and {@code render(items, revision)} showing items shaped
     * {@code {id, label}} as a card table of one column.
     */
    private Object evalCardTable(String body) {
        return evalBuilders("""
                const target = document.body.appendChild(document.createElement('div'));
                const row = item => {
                    const tr = document.createElement('tr');
                    tr.append(m.textCell(item.label));
                    return tr;
                };
                const render = (items, revision) =>
                    m.cardTable(target, ['Label'], items, {key: item => item.id, row, revision});
                """ + body);
    }

    /**
     * Runs {@code body}, a function body over the keyed module {@code m}, with an empty
     * {@code parent} in the document and {@code options} whose {@code build} turns an
     * {@code {id, label}} item into {@code <div><button>label</button></div>} and counts its
     * calls in {@code builds}.
     */
    private Object evalKeyed(String body) {
        return importModule("shared/keyed.js", """
                (async () => {
                    const parent = document.body.appendChild(document.createElement('div'));
                    let builds = 0;
                    const build = item => {
                        builds++;
                        const row = document.createElement('div');
                        row.appendChild(document.createElement('button')).textContent = item.label;
                        return row;
                    };
                    const options = {key: item => item.id, build};
                    const labels = () => [...parent.children].map(child => child.textContent).join(',');
                """ + body + "})()");
    }

    /**
     * Runs {@code body}, an async function body over a {@code client} whose requests wait in
     * {@code calls} until the body resolves or rejects them, with {@code settle()} letting
     * pending work run.
     */
    private Object evalClientOverHeldRequests(String body) {
        return importModule(
                "shared/api.js",
                "(async () => {"
                        + " const calls = [];"
                        + " window.fetch = () => new Promise((resolve, reject) => calls.push({resolve, reject}));"
                        + " const settle = () => new Promise(resolve => setTimeout(resolve));"
                        + " const client = m.createClient({basePath: '/peekaboot'});"
                        + body
                        + "})()");
    }

    /**
     * Runs {@code body}, an async function body over the self-fetching-tab module {@code m},
     * with {@code seen} for what the tab rendered and a detached {@code container}.
     */
    private Object evalSelfFetchingTab(String body) {
        return importModule(
                "shared/self-fetching-tab.js",
                "(async () => { const seen = []; const container = document.createElement('div');" + body + "})()");
    }
}
