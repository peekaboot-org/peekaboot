package org.peekaboot.backend.errorpage;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.stacktrace.StackTraceFolding;
import org.peekaboot.backend.stacktrace.StackTraceFolding.FoldedTrace;
import org.peekaboot.backend.ui.InlinedStylesheets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * settings are therefore neither read nor changed by this page - they govern what its error
 * <em>responses</em> carry, and this is a page a developer is looking at on a local run.
 *
 * <p>Like the dev toolbar's shell, the stylesheets travel with the markup as well as being
 * linked, so a reader who has put an authorization gate in front of {@code /peekaboot/**} -
 * which refuses the linked copies - still gets a page they can read. Every URL it writes is
 * relative to the request's base path, so it resolves behind a
 * {@code server.servlet.context-path} too.
 */
public class PeekabootErrorView implements View {

    private static final Logger log = LoggerFactory.getLogger(PeekabootErrorView.class);

    /**
     * Every sheet the page loads, in cascade order, relative to the base path: the three shared
     * sheets and its own. Inlined as well as linked, unlike the toolbar's, which leaves
     * components.css linked only: the status pill this page renders is server-rendered markup
     * that is already there, not something a script has yet to inject.
     */
    private static final List<String> SHEETS = List.of(
            "/ui/assets/tokens.css",
            "/ui/assets/base.css",
            "/ui/assets/components.css",
            "/ui/error-page/error-page.css");

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
                        <span>Peekaboot renders this page on a local run; set <code>peekaboot.error-page.enabled=false</code> to keep your own.</span>
                    </footer>
                </main>
            </body>
            </html>""";

    private static final String EXCEPTION_DETAIL = """
                    <section class="pk-error__detail">
                        <h2 class="pk-error__exception">{{EXCEPTION}}</h2>
                        <p class="pk-error__message">{{MESSAGE}}</p>
                        {{REVEAL}}
                        <pre class="pk-error__frames" tabindex="0" aria-label="Stack trace">{{FRAMES}}</pre>
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
            readResource(PeekabootPaths.CLASSPATH_ROOT + "/ui/error-page/reveal.js");

    /**
     * Both copies of the script, in the template's head region: linked for a host whose
     * Content-Security-Policy refuses the inline copy, inline for a host behind an
     * authorization gate that refuses the linked one. Emitted only where this request's
     * folding actually hid something - the same rule {@link #REVEAL_CONTROL} follows, since a
     * script that only wires up a button which is not on the page has nothing to do.
     * {@link #REVEAL_SCRIPT} is empty where the classpath resource is unreadable, which empties
     * the inline tag's body; the linked tag is still emitted and simply points at a URL that
     * 404s. Either way the control never arms, which is the same degradation a blocked script
     * produces - the per-run disclosures still work with no script at all. Still carries its
     * own {@code {{BASE}}} token unresolved: {@link #render} substitutes this constant into the
     * page after the generic base-path pass has already run, so it resolves that token itself.
     */
    private static final String REVEAL_SCRIPT_TAGS = "    <script src=\"" + InlinedStylesheets.BASE_TOKEN
            + "/ui/error-page/reveal.js\"></script>\n" + "    <script>" + REVEAL_SCRIPT + "</script>";

    /** {@link #TEMPLATE} with the stylesheets already in place; only the per-request and per-instance values remain. */
    private static final String PAGE =
            TEMPLATE.replace("{{CSS}}", STYLESHEETS.css()).replace("{{LINKS}}", STYLESHEETS.links());

    /** {@link #detail}'s section markup, and whether folding actually hid something in it. */
    private record Detail(String html, boolean revealsSomething) {}

    private final ErrorAttributes errorAttributes;

    private final List<String> exclusions;

    private final List<String> applicationPackages;

    private final boolean fold;

    /**
     * {@code exclusions} and {@code applicationPackages} are kept in the same order
     * {@link StackTraceFolding#fold} takes them, so a reader can't swap two adjacent
     * same-typed parameters and have it silently compile as marking framework frames
     * application code.
     *
     * @param errorAttributes the application's own, so this page and its error responses
     *     describe the same failure
     * @param exclusions the frames folding hides, resolved by {@code ExclusionPatterns}
     * @param applicationPackages the packages {@code AutoConfigurationPackages} registered,
     *     which is what marks a frame as the application's own; empty where none are
     * @param fold whether framework frames fold behind a disclosure at all; {@code false}
     *     renders exactly what shipped before folding existed
     */
    public PeekabootErrorView(
            ErrorAttributes errorAttributes, List<String> exclusions, List<String> applicationPackages, boolean fold) {
        this.errorAttributes = errorAttributes;
        this.exclusions = List.copyOf(exclusions);
        this.applicationPackages = List.copyOf(applicationPackages);
        this.fold = fold;
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
        response.getWriter().write(render(PeekabootPaths.basePath(request), method(request), attributes));
    }

    /**
     * The page for one failure. {@code {{DETAIL}}} and then {@code {{REVEAL_SCRIPT_TAGS}}} are
     * filled in last, in that order: {@code {{DETAIL}}} is the one value built from a trace and
     * a message, and {@code {{REVEAL_SCRIPT_TAGS}}} carries reveal.js's own bytes verbatim -
     * nothing may be left for a later replacement to find in either, or a value meant for one
     * placeholder could be rewritten as if it were another.
     *
     * @param method the failing request's method, or {@code null} to leave it off the request line
     */
    String render(String basePath, String method, Map<String, Object> attributes) {
        Detail detail = detail(attributes);
        String withoutScript = PAGE.replace(InlinedStylesheets.BASE_TOKEN, basePath)
                .replace("{{STATUS_VARIANT}}", statusVariant(attributes))
                .replace("{{STATUS}}", escape(attributes.get("status")))
                .replace("{{REASON}}", escape(attributes.get("error")))
                .replace("{{METHOD}}", escape(method))
                .replace("{{PATH}}", escape(attributes.get("path")))
                .replace("{{DETAIL}}", detail.html());
        String scriptTags = detail.revealsSomething() ? REVEAL_SCRIPT_TAGS : "";
        return withoutScript.replace(
                "{{REVEAL_SCRIPT_TAGS}}", scriptTags.replace(InlinedStylesheets.BASE_TOKEN, basePath));
    }

    private Detail detail(Map<String, Object> attributes) {
        Object trace = attributes.get("trace");
        if (trace == null) {
            return new Detail(MESSAGE_DETAIL.replace("{{MESSAGE}}", escape(attributes.get("message"))), false);
        }
        FoldedTrace folded =
                StackTraceFolding.fold(trace.toString(), fold ? exclusions : List.of(), applicationPackages);
        boolean revealsSomething = !folded.hidden().isEmpty();
        String html = EXCEPTION_DETAIL
                .replace("{{EXCEPTION}}", escape(attributes.get("exception")))
                .replace("{{MESSAGE}}", escape(attributes.get("message")))
                .replace("{{REVEAL}}", revealsSomething ? REVEAL_CONTROL : "")
                .replace("{{FRAMES}}", StackTraceHtml.render(folded));
        return new Detail(html, revealsSomething);
    }

    /** An unreadable script leaves the per-run disclosures working - the same degradation a blocked script produces. */
    private static String readResource(String path) {
        try (InputStream in = PeekabootErrorView.class.getResourceAsStream(path)) {
            if (in == null) {
                log.warn("resource {} not found on the classpath", path);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read resource {}: {}", path, e.getMessage());
            return "";
        }
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

    /**
     * Everything this page shows arrived with the request, an exception message included, so
     * every value is escaped. The placeholder opener goes with it: a value spelling one would
     * otherwise be filled in by a later replacement.
     */
    private static String escape(Object value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value.toString()).replace("{{", "&#123;&#123;");
    }
}
