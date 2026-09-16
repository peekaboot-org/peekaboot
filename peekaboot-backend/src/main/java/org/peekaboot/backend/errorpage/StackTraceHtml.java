package org.peekaboot.backend.errorpage;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.web.util.HtmlUtils;

/**
 * A stack trace as markup: every line of it, escaped, each one carrying whether it is a frame
 * in the application's own code. A trace is forty framework frames around the two that
 * matter, and the page exists so a developer finds those two without reading the rest.
 */
final class StackTraceHtml {

    /** How {@code Throwable.printStackTrace} writes a frame line; anything else is a header, a cause or an elision. */
    private static final String FRAME_PREFIX = "\tat ";

    private static final String CLASS_LOADER_SEPARATOR = "//";

    private static final String FRAME = "pk-error__frame";

    private static final String APPLICATION_FRAME = FRAME + " " + FRAME + "--app";

    private StackTraceHtml() {}

    /** The trace as printStackTrace wrote it, line by line, with the application's own frames marked. */
    static String render(String trace, List<String> applicationPackages) {
        return trace.lines()
                .map(line -> "<span class=\"" + frameClass(line, applicationPackages) + "\">"
                        + HtmlUtils.htmlEscape(line) + "</span>")
                .collect(Collectors.joining("\n"));
    }

    private static String frameClass(String line, List<String> applicationPackages) {
        if (!line.startsWith(FRAME_PREFIX)) {
            return FRAME;
        }
        String className = className(line.substring(FRAME_PREFIX.length()));
        boolean ownCode = applicationPackages.stream().anyMatch(each -> className.startsWith(each + "."));
        return ownCode ? APPLICATION_FRAME : FRAME;
    }

    /**
     * A frame spells its class-loader name ahead of the class whenever that loader has one -
     * Boot's launcher and the devtools restart loader both do. A module name is spelled with a
     * single slash instead, which no application package starts behind.
     */
    private static String className(String frame) {
        int classLoader = frame.indexOf(CLASS_LOADER_SEPARATOR);
        return classLoader < 0 ? frame : frame.substring(classLoader + CLASS_LOADER_SEPARATOR.length());
    }
}
