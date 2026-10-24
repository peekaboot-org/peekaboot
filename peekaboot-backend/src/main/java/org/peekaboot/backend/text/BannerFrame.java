package org.peekaboot.backend.text;

/** The frame Peekaboot's log banners (startup, shutdown, security posture) are drawn in, shared so they cannot drift apart. */
public final class BannerFrame {

    public static final String SEPARATOR =
            "===========================================================================================";

    public static final String LINE =
            " ------------------------------------------------------------------------------------------";

    private BannerFrame() {}

    /** Opens a banner with its title block, ready for the report's own lines. */
    public static StringBuilder open(String title) {
        return new StringBuilder()
                .append("\n")
                .append(SEPARATOR)
                .append("\n")
                .append(" :: ")
                .append(title)
                .append(" :: \n")
                .append(SEPARATOR)
                .append("\n");
    }

    /** One line of the report, closed by the rule that separates it from the next. */
    public static void line(StringBuilder report, String text) {
        text(report, text);
        rule(report);
    }

    /** One line of the report that belongs to the same group as the next. */
    public static void text(StringBuilder report, String text) {
        report.append(text).append("\n");
    }

    /** The rule that closes a group of lines. */
    public static void rule(StringBuilder report) {
        report.append(LINE).append("\n");
    }

    public static void blankLine(StringBuilder report) {
        report.append("\n");
    }

    public static void close(StringBuilder report) {
        report.append(SEPARATOR);
    }
}
