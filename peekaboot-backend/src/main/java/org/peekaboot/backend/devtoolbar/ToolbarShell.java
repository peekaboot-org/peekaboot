package org.peekaboot.backend.devtoolbar;

import java.util.List;
import org.peekaboot.backend.ui.InlinedStylesheets;

/**
 * The dev toolbar's server-rendered shell: the bar's markup, in a declarative shadow root,
 * with the stylesheets it cannot do without carried inline.
 *
 * <p>Rendered here rather than built in the browser by toolbar.js because a reader who has
 * put Spring Security in front of {@code /peekaboot/**} - as the website's security page
 * tells them to - has that script refused along with every other path under the prefix,
 * and a bar that only exists once its script runs cannot tell them so. Rendered here, the
 * bar arrives with the page: unauthorized, it shows the notice and a real link to the
 * dashboard, where their browser can challenge them for credentials; authorized,
 * toolbar.js adopts the same markup and fills it in.
 *
 * <p>Every URL the shell writes is relative to the base path handed to {@link #render}, so
 * a page served behind a {@code server.servlet.context-path} gets links that resolve.
 */
public class ToolbarShell {

    /**
     * Every sheet the bar loads, in cascade order, relative to the base path: the three shared
     * sheets (the same list as shadow-styles.js's SHARED_SHEETS, which SharedModuleIT pins) and
     * the bar's own. Linked as well as inlined. The {@code style-src} directive's
     * {@code 'unsafe-inline'} keyword governs only inline {@code <style>} elements and style
     * attributes; a {@code <link rel="stylesheet">} is governed by the directive's source list
     * instead, so dropping {@code 'unsafe-inline'} alone leaves it untouched. A host whose CSP
     * omits that keyword therefore loses only the inline copy - the link elements already
     * written into this markup keep the bar styled. Both come from the same file, so there is
     * nothing to keep in sync.
     */
    private static final List<String> LINKED_SHEETS = List.of(
            "/ui/assets/tokens.css", "/ui/assets/base.css", "/ui/assets/components.css", "/ui/toolbar/toolbar.css");

    private static final String COMPONENTS_SHEET = "/ui/assets/components.css";

    /**
     * The linked sheets minus components.css, which is deliberately not inlined: it styles the
     * status badge and the copy control, both of which only exist once toolbar.js has injected
     * them, so a reader who cannot load the script cannot reach anything it styles either -
     * and it is the largest sheet.
     */
    private static final List<String> INLINED_SHEETS = LINKED_SHEETS.stream()
            .filter(sheet -> !COMPONENTS_SHEET.equals(sheet))
            .toList();

    private static final InlinedStylesheets STYLESHEETS = InlinedStylesheets.of(LINKED_SHEETS, INLINED_SHEETS);

    /**
     * The bar's markup with placeholders for everything that varies: the inlined CSS and the
     * stylesheet links, folded in once at construction, and the base path and data blob,
     * filled in per page.
     */
    private static final String TEMPLATE = """
            <!-- Peekaboot Dev Toolbar -->
            <div id="peekaboot-toolbar-host">
                <template shadowrootmode="open">
                    <style>{{CSS}}</style>
            {{LINKS}}
                    <div class="pk-toolbar">
                        <button type="button" class="pk-unbutton pk-toolbar__open" aria-label="Open request trace details" aria-disabled="true">
                            <span class="pk-toolbar__side">
                                <span class="pk-badge" id="pk-status"></span>
                                <span class="pk-toolbar__method" id="pk-method"></span>
                                <span class="pk-toolbar__path" id="pk-path"></span>
                                <span class="pk-toolbar__controller" id="pk-controller"></span>
                                <span class="pk-toolbar__metrics" id="pk-metrics">
                                    <span class="pk-toolbar__pending">Waiting for request…</span>
                                </span>
                            </span>
                        </button>
                        <span class="pk-toolbar__auth" id="pk-auth"><a href="{{BASE}}/" target="_blank" title="The toolbar's script or data did not load — an authorization gate or a strict Content-Security-Policy usually explains it.">Peekaboot toolbar could not start — sign in, or check that its script is allowed to load</a></span>
                        <span class="pk-toolbar__trace" id="pk-trace">-</span>
                        <a class="pk-toolbar__link pk-logo-mark" href="{{BASE}}/" target="_blank" title="Open Dashboard" aria-label="Open Peekaboot dashboard"></a>
                    </div>
                </template>
            </div>
            <script id="peekaboot-toolbar-data" type="application/json">{{DATA}}</script>
            <script src="{{BASE}}/ui/toolbar/toolbar.js" type="module"></script>
            """;

    /** {@link #TEMPLATE} with the stylesheets already in place; only the base path and the data blob remain. */
    private final String shell;

    public ToolbarShell() {
        this.shell = TEMPLATE.replace("{{CSS}}", STYLESHEETS.css()).replace("{{LINKS}}", STYLESHEETS.links());
    }

    /**
     * The complete fragment DevToolbarFilter injects before {@code </body>}.
     *
     * <p>A real {@code <button>} carries the "open trace details" action so keyboard users
     * get it for free (Enter/Space produces a native click) and assistive tech gets a proper
     * control - not a {@code role="button"} div, which ARIA defines as
     * children-presentational and could flatten the dashboard link right out of the
     * accessibility tree. The dashboard link, the sign-in notice and the copyable trace id
     * are siblings of that button rather than descendants, so each stays independently
     * reachable - and a link nested inside a button would be invalid HTML the parser moves.
     *
     * @param basePath where the browser reaches Peekaboot from this page: the {@code /peekaboot}
     *     prefix behind the request's context path
     * @param dataJson the toolbar data blob, already script-safe (see ToolbarDataProvider)
     */
    public String render(String basePath, String dataJson) {
        return shell.replace(InlinedStylesheets.BASE_TOKEN, basePath).replace("{{DATA}}", dataJson);
    }
}
