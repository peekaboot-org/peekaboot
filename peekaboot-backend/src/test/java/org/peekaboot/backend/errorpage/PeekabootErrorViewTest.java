package org.peekaboot.backend.errorpage;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootPaths;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The page Peekaboot renders where Boot would render the whitelabel page. It asks the
 * application's own ErrorAttributes for every detail it shows, so the application's
 * spring.web.error.include-* settings are neither read nor changed by it.
 */
class PeekabootErrorViewTest {

    private final PeekabootErrorView view =
            new PeekabootErrorView(new DefaultErrorAttributes(), List.of("com.example"), List.of(), false);

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/error");
        request.setServletPath("/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/boom");
        request.setAttribute(RequestDispatcher.ERROR_METHOD, "GET");
        response = new MockHttpServletResponse();
    }

    @Test
    void namesTheStatusItsReasonAndTheRequestThatFailed() throws Exception {
        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .contains("500")
                .contains("Internal Server Error")
                .contains("GET")
                .contains("/boom");
    }

    /**
     * The status is the shared badge primitive, so the page has to pick a variant. A server
     * error takes the full pill, the same tier every other Peekaboot surface shows a 5xx in.
     */
    @Test
    void fillsTheStatusPillForAServerError() throws Exception {
        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .contains("<p class=\"pk-badge pk-badge--error pk-error__status\">500</p>");
    }

    /** A client error recedes beside a server error, which is what the soft tier is for. */
    @Test
    void softensTheStatusPillForAClientError() throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .contains("<p class=\"pk-badge pk-badge--error-soft pk-error__status\">404</p>");
    }

    /**
     * A container that sets no ERROR_METHOD leaves the request line the path alone. What is
     * left where the method would have been is whitespace, which .pk-error__request's flex row
     * does not render as an item - the one reason that rule is flex rather than inline text.
     */
    @Test
    void leavesTheMethodOutOfTheRequestLineWhereTheContainerSetsNone() throws Exception {
        request.removeAttribute(RequestDispatcher.ERROR_METHOD);

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .contains("<p class=\"pk-error__request\"> <span class=\"pk-error__path\">/boom</span></p>")
                .doesNotContain("GET");
    }

    @Test
    void servesUtf8Html() throws Exception {
        view.render(Map.of(), request, response);

        assertThat(response.getContentType()).isEqualTo("text/html;charset=UTF-8");
        assertThat(response.getContentAsString()).startsWith("<!doctype html>").endsWith("</html>");
    }

    /**
     * The exception and its trace are the reason this page exists. Asserted on the rendered
     * frame markup rather than on the class name alone, which the page's own inlined
     * stylesheet spells too.
     */
    @Test
    void carriesTheExceptionAndItsStackTrace() throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("gateway unreachable"));

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .contains("java.lang.IllegalStateException")
                .contains("gateway unreachable")
                .contains("<span class=\"pk-error__frame");
    }

    /** A sendError carries a message and no exception; the page then has no trace to show. */
    @Test
    void showsTheMessageAloneWhereThereIsNoException() throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "No handler for /nope");

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .contains("404")
                .contains("No handler for /nope")
                .doesNotContain("<span class=\"pk-error__frame");
    }

    /** The path is whatever the request carried, and it is written into markup. */
    @Test
    void escapesMarkupInTheRequestPath() throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/<script>alert(1)</script>");

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .doesNotContain("<script>alert(1)")
                .contains("&lt;script&gt;");
    }

    /**
     * {@code {{DETAIL}}} is filled in last, so a raw "{{DETAIL}}" surviving into an earlier
     * replacement (the path, here) would be caught by that later call too, stamping a second
     * detail section where the request path is shown. escape() neutralises "{{" for exactly
     * this reason.
     */
    @Test
    void neutralisesAPlaceholderLookingPathSoItCannotReopenAReplacement() throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/{{DETAIL}}");

        view.render(Map.of(), request, response);

        String html = response.getContentAsString();
        assertThat(html).doesNotContain("{{DETAIL}}").contains("&#123;&#123;DETAIL}}");
        assertThat(html).containsOnlyOnce("class=\"pk-error__detail\"");
    }

    /** The message on the no-exception branch is exactly as attacker-controllable as the path. */
    @Test
    void escapesMarkupInTheMessageWhereThereIsNoException() throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "<script>alert(1)</script>");

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString())
                .doesNotContain("<script>alert(1)")
                .contains("&lt;script&gt;");
    }

    /** Behind a context path every URL the page writes has to carry that prefix, like the bar's. */
    @Test
    void prefixesItsStylesheetsWithTheContextPath() throws Exception {
        request.setContextPath("/app");

        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString()).contains("href=\"/app/peekaboot/ui/assets/tokens.css\"");
    }

    /** The page is Peekaboot's, not the application's: it says so and names the way out. */
    @Test
    void saysWhereThePageCameFromAndHowToTurnItOff() throws Exception {
        view.render(Map.of(), request, response);

        assertThat(response.getContentAsString()).contains("peekaboot.error-page.enabled=false");
    }

    @Test
    void offersOneControlThatOpensEveryHiddenRun() {
        String page = render(view(List.of("org.springframework"), true));

        assertThat(page)
                .contains("class=\"pk-btn pk-btn--small pk-error__reveal\"")
                .contains("aria-pressed=\"false\"")
                .contains("ui/error-page/reveal.js");
    }

    /**
     * Inline for a host that forbids the fetch, linked for one that forbids inline script.
     * Asserted against the resource's own bytes, read off the classpath the same way
     * {@code PeekabootErrorView} reads them, rather than against a marker string in the
     * source: a {@code containsPattern} on the source text cannot tell a script that runs
     * from one that merely mentions the right words in a comment.
     */
    @Test
    void shipsTheRevealScriptThroughBothChannels() throws IOException {
        String page = render(view(List.of("org.springframework"), true));

        assertThat(page).contains("<script>" + revealScript() + "</script>");
        assertThat(page).contains("<script src=\"/peekaboot/ui/error-page/reveal.js\"></script>");
    }

    /**
     * Asserted against the button's own markup, not the bare class name: error-page.css'
     * {@code .pk-error__reveal} selector is inlined into every page's {@code <style>} block
     * regardless of whether the button renders, so the bare string appears there too.
     */
    @Test
    void offersNoControlWithFoldingOff() {
        String page = render(view(List.of("org.springframework"), false));

        assertThat(page)
                .doesNotContain("class=\"pk-btn pk-btn--small pk-error__reveal\"")
                .doesNotContain("reveal.js");
    }

    /** Folding can be on and still hide nothing - an exclusion list that matches no frame is the same as fold=false here. */
    @Test
    void offersNoControlWhereFoldingHidesNothing() {
        String page = render(view(List.of("no.such.package"), true));

        assertThat(page)
                .doesNotContain("class=\"pk-btn pk-btn--small pk-error__reveal\"")
                .doesNotContain("reveal.js");
    }

    /**
     * {@code {{REVEAL_SCRIPT_TAGS}}} is substituted last, against a page that already carries
     * the trace - and the trace's own first line repeats the exception's message, which is
     * exactly the kind of request-influenced text that could spell that placeholder.
     * {@code StackTraceHtml} neutralises a double brace for this reason; this pins the whole
     * path end to end, the way {@link #neutralisesAPlaceholderLookingPathSoItCannotReopenAReplacement()}
     * pins it for the request path.
     */
    @Test
    void doesNotReopenTheScriptPlaceholderFromAPlaceholderLookingMessage() {
        String page = render(view(List.of("org.springframework"), true), exceptionSpellingTheScriptPlaceholder());

        assertThat(page)
                .doesNotContain("{{REVEAL_SCRIPT_TAGS}}")
                .contains("&#123;&#123;REVEAL_SCRIPT_TAGS}}")
                .containsOnlyOnce("<script>")
                .containsOnlyOnce("<script src=\"/peekaboot/ui/error-page/reveal.js\">");
    }

    private static Throwable exceptionSpellingTheScriptPlaceholder() {
        IllegalStateException exception = new IllegalStateException("{{REVEAL_SCRIPT_TAGS}}");
        exception.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.example.Widget", "process", "Widget.java", 10),
            new StackTraceElement(
                    "org.springframework.web.servlet.DispatcherServlet", "doDispatch", "DispatcherServlet.java", 1234)
        });
        return exception;
    }

    private static String revealScript() throws IOException {
        try (InputStream in = PeekabootErrorViewTest.class.getResourceAsStream(
                PeekabootPaths.CLASSPATH_ROOT + "/ui/error-page/reveal.js")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private PeekabootErrorView view(List<String> exclusions, boolean fold) {
        return new PeekabootErrorView(new DefaultErrorAttributes(), List.of("com.example"), exclusions, fold);
    }

    /**
     * Renders {@code view} for a request whose exception carries a synthetic trace of one
     * application frame and a run of framework frames - real enough to fold, without depending
     * on whatever frames the JUnit runner itself happens to be on the stack with.
     */
    private String render(PeekabootErrorView view) {
        return render(view, exceptionWithFrameworkFrames());
    }

    private String render(PeekabootErrorView view, Throwable exception) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/error");
        req.setServletPath("/error");
        req.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        req.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/boom");
        req.setAttribute(RequestDispatcher.ERROR_EXCEPTION, exception);
        MockHttpServletResponse res = new MockHttpServletResponse();
        try {
            view.render(Map.of(), req, res);
            return res.getContentAsString();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Throwable exceptionWithFrameworkFrames() {
        IllegalStateException exception = new IllegalStateException("boom");
        exception.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.example.Widget", "process", "Widget.java", 10),
            new StackTraceElement(
                    "org.springframework.web.servlet.DispatcherServlet", "doDispatch", "DispatcherServlet.java", 1234),
            new StackTraceElement(
                    "org.springframework.web.servlet.FrameworkServlet", "processRequest", "FrameworkServlet.java", 1000)
        });
        return exception;
    }
}
