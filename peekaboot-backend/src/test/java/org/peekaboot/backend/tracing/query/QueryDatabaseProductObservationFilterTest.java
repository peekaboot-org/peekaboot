package org.peekaboot.backend.tracing.query;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import net.ttddyy.observation.tracing.ConnectionContext;
import net.ttddyy.observation.tracing.DataSourceObservationListener;
import net.ttddyy.observation.tracing.QueryContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.testsupport.RecordingObservations;

/**
 * datasource-micrometer fills the same {@link QueryContext} with or without its OpenTelemetry module, which only
 * swaps the convention, so a context the listener filled covers both modes.
 */
class QueryDatabaseProductObservationFilterTest {

    private static final String PRODUCT_KEY = "peekaboot.db.product";

    private final QueryDatabaseProductObservationFilter filter = new QueryDatabaseProductObservationFilter();

    /** The URL comes from the connection the listener saw acquired; no second connection is opened for it. */
    @Test
    void namesTheProductOfAQueryRunThroughDatasourceMicrometer() throws SQLException {
        RecordingObservations observations = new RecordingObservations();
        observations.registry().observationConfig().observationFilter(filter);
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        DataSourceObservationListener listener = new DataSourceObservationListener(observations.registry());
        DataSource dataSource = ProxyDataSourceBuilder.create(h2)
                .listener(listener)
                .methodListener(listener)
                .build();

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }

        List<QueryContext> queries = observations.stopped().stream()
                .filter(QueryContext.class::isInstance)
                .map(QueryContext.class::cast)
                .toList();
        assertThat(queries).hasSize(1);
        assertThat(productOf(queries.getFirst())).isEqualTo("H2");
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:h2:mem:orders, H2",
        "jdbc:h2:file:./data/orders, H2",
        "jdbc:mysql://db.internal:3306/inventory, MYSQL",
        "jdbc:postgresql://db.internal:5432/orders, POSTGRESQL",
        "jdbc:mariadb://db.internal:3306/orders, MARIADB",
        "jdbc:sqlserver://db.internal:1433;databaseName=orders, SQLSERVER",
        "jdbc:oracle:thin:@//db.internal:1521/ORCL, ORACLE",
        "jdbc:hsqldb:mem:orders, HSQLDB",
        "jdbc:derby:memory:orders, DERBY",
        "jdbc:sqlite:orders.db, SQLITE"
    })
    void namesTheProductTheUrlNames(String url, String product) {
        QueryContext query = new QueryContext();
        query.setUrl(url);

        assertThat(productOf(filter.map(query))).isEqualTo(product);
    }

    /** No tag lets the Queries tab fall back to what the instrumentation named. */
    @Test
    void leavesAQueryWithoutAUrlUntagged() {
        QueryContext query = new QueryContext();

        assertThat(keysOf(filter.map(query))).isEmpty();
    }

    @Test
    void leavesAQueryWhoseUrlNamesNoKnownProductUntagged() {
        QueryContext query = new QueryContext();
        query.setUrl("jdbc:p6spy:mysql://db.internal:3306/inventory");

        assertThat(keysOf(filter.map(query))).isEmpty();
    }

    /** A connection span knows its URL too, but it is no query. */
    @Test
    void leavesAConnectionObservationUntouched() {
        ConnectionContext connection = new ConnectionContext();
        connection.setUrl("jdbc:h2:mem:orders");

        assertThat(keysOf(filter.map(connection))).isEmpty();
    }

    /**
     * The product is the same for every query of a DataSource, but a low-cardinality key would also become a tag
     * of the application's {@code jdbc.query} timer.
     */
    @Test
    void keepsTheProductOffTheQueryTimersTags() {
        QueryContext query = new QueryContext();
        query.setUrl("jdbc:h2:mem:orders");

        assertThat(filter.map(query).getLowCardinalityKeyValue(PRODUCT_KEY)).isNull();
    }

    private static String productOf(Observation.Context context) {
        KeyValue product = context.getHighCardinalityKeyValue(PRODUCT_KEY);
        assertThat(product)
                .as("the key the tracing handler turns into the span tag")
                .isNotNull();
        return product.getValue();
    }

    private static List<String> keysOf(Observation.Context context) {
        return context.getAllKeyValues().stream().map(KeyValue::getKey).toList();
    }
}
