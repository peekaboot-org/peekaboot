package org.peekaboot.backend.errorpage;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.ui.InlinedStylesheets;
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
                        <pre class="pk-error__frames" tabindex="0" aria-label="Stack trace">{{FRAMES}}</pre>
                    </section>
            """;

    /** A sendError carries a message and no exception, so there is no trace and nothing to head it with. */
    private static final String MESSAGE_DETAIL = """
                    <section class="pk-error__detail">
                        <p class="pk-error__message">{{MESSAGE}}</p>
                    </section>
            """;

    /** {@link #TEMPLATE} with the stylesheets already in place; only the per-request values remain. */
    private static final String PAGE =
            TEMPLATE.replace("{{CSS}}", STYLESHEETS.css()).replace("{{LINKS}}", STYLESHEETS.links());

    private final ErrorAttributes errorAttributes;

    private final List<String> applicationPackages;

    /**
     * @param errorAttributes the application's own, so this page and its error responses
     *     describe the same failure
     * @param applicationPackages the packages {@code AutoConfigurationPackages} registered,
     *     which is what marks a frame as the application's own; empty where none are
     */
    public PeekabootErrorView(ErrorAttributes errorAttributes, List<String> applicationPackages) {
        this.errorAttributes = errorAttributes;
        this.applicationPackages = List.copyOf(applicationPackages);
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
     * The page for one failure. {@code {{DETAIL}}} is filled in last: it is the one value built
     * from a trace and a message, and nothing may be left for a later replacement to find in it.
     *
     * @param method the failing request's method, or {@code null} to leave it off the request line
     */
    String render(String basePath, String method, Map<String, Object> attributes) {
        return PAGE.replace(InlinedStylesheets.BASE_TOKEN, basePath)
                .replace("{{STATUS_VARIANT}}", statusVariant(attributes))
                .replace("{{STATUS}}", escape(attributes.get("status")))
                .replace("{{REASON}}", escape(attributes.get("error")))
                .replace("{{METHOD}}", escape(method))
                .replace("{{PATH}}", escape(attributes.get("path")))
                .replace("{{DETAIL}}", detail(attributes));
    }

    private String detail(Map<String, Object> attributes) {
        Object trace = attributes.get("trace");
        if (trace == null) {
            return MESSAGE_DETAIL.replace("{{MESSAGE}}", escape(attributes.get("message")));
        }
        return EXCEPTION_DETAIL
                .replace("{{EXCEPTION}}", escape(attributes.get("exception")))
                .replace("{{MESSAGE}}", escape(attributes.get("message")))
                .replace("{{FRAMES}}", StackTraceHtml.render(trace.toString(), applicationPackages));
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
