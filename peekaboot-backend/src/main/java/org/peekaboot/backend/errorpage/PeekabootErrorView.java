package org.peekaboot.backend.errorpage;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.stacktrace.StackTraceRules;
import org.peekaboot.backend.ui.ClasspathText;
import org.peekaboot.backend.ui.InlinedStylesheets;
import org.peekaboot.backend.ui.Placeholders;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.ErrorAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.View;
import org.springframework.web.util.HtmlUtils;

/**
 * The page Peekaboot renders where Spring Boot would render its whitelabel page, or where the
 * application's own error page would render with {@code peekaboot.error-page.override} set: the
 * failing request, the exception and its stack trace, with the application's own frames marked.
 *
 * <p>Every detail comes from the application's own {@link ErrorAttributes}, asked for with
 * every {@code Include} switched on. The application's {@code spring.web.error.include-*}
 * settings are therefore neither read nor changed by this page. They govern what its error
 * <em>responses</em> carry; this page is for the developer debugging the failure, which is why
 * {@code SecurityPosture} warns when a deployment has it on.
 *
 * <p>Like the dev toolbar's shell, the stylesheets travel with the markup as well as being
 * linked, so a reader who has put an authorization gate in front of {@code /peekaboot/**} -
 * which refuses the linked copies - still gets a page they can read. Every URL it writes is
 * relative to the request's base path, so it resolves behind a
 * {@code server.servlet.context-path} too.
 */
public class PeekabootErrorView implements View {

    /**
     * Every sheet the page loads, in cascade order, relative to the base path: the three shared
     * sheets and its own. Inlined as well as linked, unlike the toolbar's, which leaves
     * components.css linked only: the status pill this page renders is server-rendered markup
     * that is already there, not something a script has yet to inject.
     */
    private static final List<String> SHEETS = InlinedStylesheets.sharedSheetsAnd("/ui/error-page/error-page.css");

    private static final InlinedStylesheets STYLESHEETS = InlinedStylesheets.of(SHEETS, SHEETS);

    /** Everything the page can show, so what it shows is decided here rather than by the application's settings. */
    private static final ErrorAttributeOptions ALL_DETAILS =
            ErrorAttributeOptions.of(ErrorAttributeOptions.Include.values());

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{{STATUS}} {{REASON}}</title>
                <script src="{{BASE}}/ui/assets/theme-boot.js"></script>
            {{REVEAL_SCRIPT_TAGS}}
                <style>{{CSS}}</style>
            {{LINKS}}
            </head>
            <body>
                <main class="pk-error">
                    <header class="pk-error__head">
                        <p class="pk-badge {{STATUS_VARIANT}} pk-error__status">{{STATUS}}</p>
                        <h1 class="pk-error__reason">{{REASON}}</h1>
                        <p class="pk-error__request">{{METHOD}} <span class="pk-error__path">{{PATH}}</span></p>
                    </header>
            {{DETAIL}}
                    <footer class="pk-error__footer">
                        <a class="pk-logo-mark pk-error__mark" href="{{BASE}}/" aria-label="Open Peekaboot dashboard"></a>
                        <span>Rendered by Peekaboot; set <code>peekaboot.error-page.enabled=false</code> to keep your own error page.</span>
                    </footer>
                </main>
            </body>
            </html>""";

    private static final String EXCEPTION_DETAIL = """
                    <section class="pk-error__detail">
                        <h2 class="pk-error__exception">{{EXCEPTION}}</h2>
                        <p class="pk-error__message">{{MESSAGE}}</p>
                        {{REVEAL}}
                        <pre class="pk-stack-trace pk-error__frames" tabindex="0" aria-label="Stack trace">{{FRAMES}}</pre>
                    </section>
            """;

    /** A sendError carries a message and no exception, so there is no trace and nothing to head it with. */
    private static final String MESSAGE_DETAIL = """
                    <section class="pk-error__detail">
                        <p class="pk-error__message">{{MESSAGE}}</p>
                    </section>
            """;

    /** Rendered only where folding actually hid something - a control that reveals nothing must not appear. */
    private static final String REVEAL_CONTROL = """
                        <button type="button" class="pk-btn pk-btn--small pk-error__reveal" aria-pressed="false">Show full stack trace</button>
            """;

    /** Shipped inline as well as linked; see the class comment on why one channel is not enough. */
    private static final String REVEAL_SCRIPT =
            ClasspathText.read("/ui/error-page/reveal.js").orElse("");

    /**
     * Both copies of the script, in the template's head region: linked for a host whose
     * Content-Security-Policy refuses the inline copy, inline for a host behind an
     * authorization gate that refuses the linked one. Emitted only where this request's
     * folding actually hid something - the same rule {@link #REVEAL_CONTROL} follows, since a
     * script that only wires up a button which is not on the page has nothing to do.
     * {@link #REVEAL_SCRIPT} is empty where the classpath resource is unreadable; the linked
     * tag then points at a URL that 404s, and the control never arms, the same degradation a
     * blocked script produces.
     */
    private static final String REVEAL_SCRIPT_TAGS = "    <script src=\"{{BASE}}/ui/error-page/reveal.js\"></script>\n"
            + "    <script>{{REVEAL_SCRIPT}}</script>";

    /** {@link #TEMPLATE} with the stylesheets in place; their {@code {{BASE}}} tokens are filled per request with the rest. */
    private static final String PAGE = STYLESHEETS.fillInto(TEMPLATE);

    /** {@link #detail}'s section markup, and whether folding actually hid something in it. */
    private record Detail(String html, boolean revealsSomething) {}

    private final ErrorAttributes errorAttributes;

    private final StackTraceRules stackTraceRules;

    private final PeekabootPaths paths;

    /**
     * @param errorAttributes the application's own, so this page and its error responses
     *     describe the same failure
     * @param stackTraceRules the folding the Logs tab uses too
     * @param paths where the page's own URLs point, which the DispatcherServlet's prefix moves
     */
    public PeekabootErrorView(ErrorAttributes errorAttributes, StackTraceRules stackTraceRules, PeekabootPaths paths) {
        this.errorAttributes = errorAttributes;
        this.stackTraceRules = stackTraceRules;
        this.paths = paths;
    }

    @Override
    public String getContentType() {
        return "text/html;charset=UTF-8";
    }

    @Override
    public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Map<String, Object> attributes =
                errorAttributes.getErrorAttributes(new ServletWebRequest(request), ALL_DETAILS);
        response.setContentType(getContentType());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(render(paths.basePath(request), method(request), attributes));
    }

    /** @param method the failing request's method, or {@code null} to leave it off the request line */
    String render(String basePath, String method, Map<String, Object> attributes) {
        String base = escape(basePath);
        Detail detail = detail(attributes);
        String scriptTags = detail.revealsSomething()
                ? Placeholders.fill(REVEAL_SCRIPT_TAGS, Map.of("BASE", base, "REVEAL_SCRIPT", REVEAL_SCRIPT))
                : "";
        return Placeholders.fill(
                PAGE,
                Map.of(
                        "BASE", base,
                        "STATUS_VARIANT", statusVariant(attributes),
                        "STATUS", escape(attributes.get("status")),
                        "REASON", escape(attributes.get("error")),
                        "METHOD", escape(method),
                        "PATH", escape(attributes.get("path")),
                        "DETAIL", detail.html(),
                        "REVEAL_SCRIPT_TAGS", scriptTags));
    }

    private Detail detail(Map<String, Object> attributes) {
        Object trace = attributes.get("trace");
        if (trace == null) {
            return new Detail(
                    Placeholders.fill(MESSAGE_DETAIL, Map.of("MESSAGE", escape(attributes.get("message")))), false);
        }
        FoldedTrace folded = stackTraceRules.fold(trace.toString());
        boolean revealsSomething = !folded.hidden().isEmpty();
        String html = Placeholders.fill(
                EXCEPTION_DETAIL,
                Map.of(
                        "EXCEPTION", escape(attributes.get("exception")),
                        "MESSAGE", escape(attributes.get("message")),
                        "REVEAL", revealsSomething ? REVEAL_CONTROL : "",
                        "FRAMES", StackTraceHtml.render(folded)));
        return new Detail(html, revealsSomething);
    }

    /** A client error recedes beside a server error, the two tiers every Peekaboot surface shows a status in. */
    private static String statusVariant(Map<String, Object> attributes) {
        boolean clientError = attributes.get("status") instanceof Integer status && status >= 400 && status < 500;
        return clientError ? "pk-badge--error-soft" : "pk-badge--error";
    }

    /** A container that sets no ERROR_METHOD - an async dispatch is the usual one - leaves it off. */
    private static String method(HttpServletRequest request) {
        Object method = request.getAttribute(RequestDispatcher.ERROR_METHOD);
        return method == null ? null : method.toString();
    }

    /** Everything this page shows arrived with the request, an exception message included, so every value is escaped. */
    private static String escape(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value.toString());
    }
}
