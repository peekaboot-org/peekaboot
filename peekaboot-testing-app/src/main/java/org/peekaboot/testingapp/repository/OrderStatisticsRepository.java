package org.peekaboot.testingapp.repository;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Order counts per status, from a query that is slow on purpose: pg_sleep holds it at about
 * 80 ms, past Peekaboot's 50 ms SLOW_QUERY threshold, so the trace always carries that issue.
 */
@Repository
public class OrderStatisticsRepository {

    /** pause is the LEFT JOIN's preserved side, so the sleep runs even with no orders to count. */
    private static final String COUNT_BY_STATUS = """
            WITH pause AS (SELECT pg_sleep(0.08) AS slept)
            SELECT o.status AS status, count(o.id) AS order_count
            FROM pause LEFT JOIN customer_order o ON TRUE
            GROUP BY o.status
            HAVING count(o.id) > 0
            ORDER BY o.status""";

    private final JdbcClient jdbcClient;

    /** The orders DataSource: the inventory's is not a default candidate, so this is the only one injected here. */
    public OrderStatisticsRepository(DataSource dataSource) {

        this.jdbcClient = JdbcClient.create(dataSource);
    }

    public List<StatusCount> countByStatus() {

        return jdbcClient.sql(COUNT_BY_STATUS).query(StatusCount.class).list();
    }
}
