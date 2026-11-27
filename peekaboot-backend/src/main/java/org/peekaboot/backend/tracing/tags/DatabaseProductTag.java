package org.peekaboot.backend.tracing.tags;

/** The key Peekaboot puts on a query span to name its database product. */
public final class DatabaseProductTag {

    /**
     * The {@code net.osslabz.jdbc.DatabaseProduct} constant the query's JDBC URL names, such as {@code H2}; absent
     * where the URL is unknown or names no product the parser knows.
     */
    public static final String KEY = "peekaboot.db.product";

    private DatabaseProductTag() {}
}
