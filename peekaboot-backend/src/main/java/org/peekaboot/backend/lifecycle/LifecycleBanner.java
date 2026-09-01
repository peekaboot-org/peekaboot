package org.peekaboot.backend.lifecycle;

/** The frame Peekaboot's lifecycle banners are drawn in, shared so the two cannot drift apart. */
final class LifecycleBanner {

    static final String SEPARATOR =
            "===========================================================================================";

    static final String LINE =
            " ------------------------------------------------------------------------------------------";

    private LifecycleBanner() {}

    /** Opens a banner with its title block, ready for the report's own lines. */
    static StringBuilder open(String title) {
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

    static void close(StringBuilder report) {
        report.append(SEPARATOR);
    }
}
