package org.peekaboot.backend.mapper.trace;

import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.engine.jdbc.internal.FormatStyle;
import org.hibernate.engine.jdbc.internal.Formatter;

/**
 * Hibernate's {@code format_sql} layout ({@link FormatStyle#BASIC}), so a statement reads the
 * way the application's own SQL log prints it. The auto-configuration creates it only with
 * Hibernate on the classpath.
 */
public final class HibernateSqlFormatter implements SqlFormatter {

    private static final Pattern STATEMENTS = Pattern.compile(DbSpans.STATEMENT_SEPARATOR, Pattern.LITERAL);

    private final Formatter formatter = FormatStyle.BASIC.getFormatter();

    @Override
    public String format(String sql) {
        return STATEMENTS
                .splitAsStream(sql)
                .map(this::formatStatement)
                .collect(Collectors.joining(DbSpans.STATEMENT_SEPARATOR));
    }

    /** BASIC opens every statement with a line break, which in a code block is a blank first line. */
    private String formatStatement(String statement) {
        String formatted = formatter.format(statement);
        return formatted.startsWith("\n") ? formatted.substring(1) : formatted;
    }
}
