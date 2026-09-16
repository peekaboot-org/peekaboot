package org.peekaboot.backend.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.peekaboot.backend.config.PeekabootPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stylesheet, read from the classpath, comment-stripped and {@code url()}-rewritten, both
 * linked and inlined - so a server-rendered surface stays styled whether it is refused by an
 * authorization gate (which the linked copy cannot survive) or by a Content-Security-Policy
 * that drops inline styles (which the inlined copy cannot survive). Both come from the same
 * file, so there is nothing to keep in sync.
 */
public final class InlinedStylesheets {

    private static final Logger log = LoggerFactory.getLogger(InlinedStylesheets.class);

    private static final String CLASSPATH_ROOT = PeekabootPaths.CLASSPATH_ROOT;

    /** Stands in for the base path until a renderer knows it; also survives into the inlined CSS. */
    public static final String BASE_TOKEN = "{{BASE}}";

    /** A relative {@code url()} target; absolute and scheme-qualified ones are left alone. */
    private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)");

    /**
     * A CSS comment block; the sheets carry their design rationale in them, which a host page
     * need not download. Naive by design: it pairs comment delimiters wherever they appear,
     * where a CSS parser ignores them inside a string or a {@code url()}. Every inlined sheet
     * must therefore keep both delimiters out of its string and {@code url()} tokens - a
     * {@code content} value spelling an opener, or a data URI carrying a closer, has the
     * stripper swallow the declarations in between.
     */
    private static final Pattern CSS_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    private final List<String> linked;
    private final List<String> inlined;

    private InlinedStylesheets(List<String> linked, List<String> inlined) {
        this.linked = linked;
        this.inlined = inlined;
    }

    /**
     * Builds the css/links pair for one set of stylesheets.
     *
     * @param linked sheets to write a {@code <link rel="stylesheet">} for, served paths relative
     *     to the base path
     * @param inlined sheets to read from the classpath and concatenate into {@link #css()},
     *     served paths relative to the base path
     */
    public static InlinedStylesheets of(List<String> linked, List<String> inlined) {
        return new InlinedStylesheets(linked, inlined);
    }

    /** One {@code <link rel="stylesheet">} line per linked sheet, newline-joined. */
    public String links() {
        return linked.stream()
                .map(href -> "        <link rel=\"stylesheet\" href=\"" + BASE_TOKEN + href + "\">")
                .collect(Collectors.joining("\n"));
    }

    /** The concatenated, comment-stripped, {@code url()}-rewritten CSS of the inlined sheets. */
    public String css() {
        StringBuilder css = new StringBuilder();
        for (String servedPath : inlined) {
            String sheet = readSheet(servedPath);
            if (sheet != null) {
                css.append(resolveRelativeUrls(stripComments(sheet), servedPath))
                        .append('\n');
            }
        }
        return css.toString();
    }

    private static String stripComments(String css) {
        return CSS_COMMENT.matcher(css).replaceAll("");
    }

    private static String readSheet(String servedPath) {
        try (InputStream in = InlinedStylesheets.class.getResourceAsStream(CLASSPATH_ROOT + servedPath)) {
            if (in == null) {
                // peekaboot-frontend is not on the classpath, so whatever surface is rendering
                // has more missing than its styling - this warning is the least of its problems.
                log.warn("stylesheet {} not found on the classpath", servedPath);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read stylesheet {}: {}", servedPath, e.getMessage());
            return null;
        }
    }

    /**
     * A relative {@code url()} resolves against the stylesheet that contains it. Inlined into
     * the page the same text would resolve against the page instead, so each one is rewritten
     * to the path it had while the sheet was still being served from its own URL - behind the
     * base-path token, which the renderer fills in per request.
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
            return "url('" + BASE_TOKEN
                    + sheetUri.resolve(new URI(null, null, target, null)).getPath() + "')";
        } catch (URISyntaxException e) {
            log.debug("Leaving unparseable stylesheet url({}) alone: {}", target, e.getMessage());
            return "url('" + target + "')";
        }
    }
}
