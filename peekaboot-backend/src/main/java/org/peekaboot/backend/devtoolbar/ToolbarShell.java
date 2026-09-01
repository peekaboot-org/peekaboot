package org.peekaboot.backend.devtoolbar;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public class ToolbarShell {

    private static final Logger log = LoggerFactory.getLogger(ToolbarShell.class);

    private static final String CLASSPATH_ROOT = "/static";

    /**
     * Every sheet the bar's own appearance depends on, in cascade order. components.css is
     * deliberately absent: it styles the status badge and the copy control, both of which
     * only exist once toolbar.js has injected them, so a reader who cannot load the script
     * cannot reach anything it styles either - and it is 12 KB.
     */
    private static final List<String> INLINED_SHEETS = List.of(
            "/peekaboot/ui/assets/tokens.css", "/peekaboot/ui/assets/base.css", "/peekaboot/ui/toolbar/toolbar.css");

    /**
     * Linked as well as inlined. A host page whose CSP omits {@code style-src 'unsafe-inline'}
     * drops the inline copy; the toolbar works on such a page because toolbar.js creates
     * its link elements through the CSSOM, which CSP does not govern. Keeping the links means
     * that reader loses nothing, while the inline copy serves the reader whose gate refuses
     * links. Both come from the same file, so there is nothing to keep in sync.
     */
    private static final List<String> LINKED_SHEETS = List.of(
            "/peekaboot/ui/assets/tokens.css",
            "/peekaboot/ui/assets/base.css",
            "/peekaboot/ui/assets/components.css",
            "/peekaboot/ui/toolbar/toolbar.css");

    /** A relative {@code url()} target; absolute and scheme-qualified ones are left alone. */
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)");

    private final String inlinedCss;

    public ToolbarShell() {
        this.inlinedCss = loadInlinedCss();
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
     */
    public String render(String dataJson) {
        return """
            <!-- Peekaboot Dev Toolbar -->
            <div id="peekaboot-toolbar-host">
                <template shadowrootmode="open">
                    <style>{{CSS}}</style>
            {{LINKS}}
                    <div class="pk-toolbar">
                        <button type="button" class="pk-toolbar__open" aria-label="Open request trace details" aria-disabled="true">
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
                        <span class="pk-toolbar__auth" id="pk-auth"><a href="{{BASE}}/" target="_blank" title="Peekaboot's data is behind an authorization gate on this deployment">Sign in to see this request</a></span>
                        <span class="pk-toolbar__trace" id="pk-trace">-</span>
                        <a class="pk-toolbar__link" href="{{BASE}}/" target="_blank" title="Open Dashboard" aria-label="Open Peekaboot dashboard"></a>
                    </div>
                </template>
            </div>
            <script id="peekaboot-toolbar-data" type="application/json">{{DATA}}</script>
            <script src="{{BASE}}/ui/toolbar/toolbar.js" type="module"></script>
            """.replace("{{CSS}}", inlinedCss)
                .replace("{{LINKS}}", stylesheetLinks())
                .replace("{{BASE}}", ToolbarDataProvider.BASE_PATH)
                .replace("{{DATA}}", dataJson);
    }

    private static String stylesheetLinks() {
        return LINKED_SHEETS.stream()
                .map(href -> "        <link rel=\"stylesheet\" href=\"" + href + "\">")
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static String loadInlinedCss() {
        StringBuilder css = new StringBuilder();
        for (String servedPath : INLINED_SHEETS) {
            String sheet = readSheet(servedPath);
            if (sheet != null) {
                css.append(resolveRelativeUrls(sheet, servedPath)).append('\n');
            }
        }
        return css.toString();
    }

    private static String readSheet(String servedPath) {
        try (InputStream in = ToolbarShell.class.getResourceAsStream(CLASSPATH_ROOT + servedPath)) {
            if (in == null) {
                // peekaboot-frontend is not on the classpath, which also means toolbar.js
                // is not being served - the bar has bigger problems than its styling.
                log.warn("Dev toolbar stylesheet {} not found on the classpath; the bar will be unstyled", servedPath);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read dev toolbar stylesheet {}: {}", servedPath, e.getMessage());
            return null;
        }
    }

    /**
     * A relative {@code url()} resolves against the stylesheet that contains it. Inlined into
     * the page the same text would resolve against the page instead, so each one is rewritten
     * to the path it had while the sheet was still being served from its own URL.
     */
    private static String resolveRelativeUrls(String css, String servedPath) {
        URI sheetUri = URI.create(servedPath);
        Matcher matcher = CSS_URL.matcher(css);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(rewriteUrl(sheetUri, matcher.group(2))));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static String rewriteUrl(URI sheetUri, String target) {
        if (target.startsWith("/") || target.startsWith("#") || target.contains(":")) {
            return "url('" + target + "')";
        }
        try {
            return "url('" + sheetUri.resolve(new URI(null, null, target, null)).getPath() + "')";
        } catch (URISyntaxException e) {
            log.debug("Leaving unparseable stylesheet url({}) alone: {}", target, e.getMessage());
            return "url('" + target + "')";
        }
    }
}
