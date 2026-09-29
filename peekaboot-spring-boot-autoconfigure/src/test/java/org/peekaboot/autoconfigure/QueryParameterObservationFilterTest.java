package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import net.ttddyy.observation.tracing.DataSourceObservationListener;
import net.ttddyy.observation.tracing.QueryContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.mapper.trace.DbSpans;
import tools.jackson.databind.JsonNode;

/**
 * Drives the filter the way an application does: a real H2 DataSource behind datasource-proxy
 * and datasource-micrometer's own listener, left at its defaults (parameter values off), and
 * the context read back in a handler's onStop - where the tracing handler tags the span from.
 */
class QueryParameterObservationFilterTest {

    private final List<Observation.Context> stopped = new CopyOnWriteArrayList<>();

    private DataSource dataSource;

    @BeforeEach
    void instrumentAnH2DataSource() throws SQLException {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationFilter(new QueryParameterObservationFilter())
                .observationHandler(new ObservationHandler<>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context context) {
                        stopped.add(context);
                    }
                });
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        DataSourceObservationListener listener = new DataSourceObservationListener(registry);
        dataSource = ProxyDataSourceBuilder.create(h2)
                .listener(listener)
                .methodListener(listener)
                .build();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE person (id BIGINT, name VARCHAR(100))");
        }
        stopped.clear();
    }

    /** Set out of index order on purpose: the literals follow the placeholders, not the calls. */
    @Test
    void recordsAPreparedStatementsParametersInPlaceholderOrder() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO person (id, name) VALUES (?, ?)")) {
            insert.setString(2, "O'Brien");
            insert.setLong(1, 42);
            insert.executeUpdate();
        }

        assertThat(parametersTag()).isEqualTo("[[\"42\",\"'O''Brien'\"]]");
    }

    @Test
    void recordsOneParameterSetPerBatchEntry() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO person (id, name) VALUES (?, ?)")) {
            insert.setLong(1, 1);
            insert.setString(2, "Ann");
            insert.addBatch();
            insert.setLong(1, 2);
            insert.setString(2, "Bob");
            insert.addBatch();
            insert.executeBatch();
        }

        assertThat(parametersTag()).isEqualTo("[[\"1\",\"'Ann'\"],[\"2\",\"'Bob'\"]]");
    }

    /** A runaway batch should not turn one query into thousands of high-cardinality tag entries. */
    @Test
    void capsTheNumberOfParameterSetsPerBatch() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO person (id, name) VALUES (?, ?)")) {
            for (long id = 1; id <= QueryParameterObservationFilter.MAX_PARAMETER_SETS + 1; id++) {
                insert.setLong(1, id);
                insert.setString(2, "n" + id);
                insert.addBatch();
            }
            insert.executeBatch();
        }

        JsonNode sets = PeekabootJson.MAPPER.readTree(parametersTag());
        assertThat(sets).hasSize(QueryParameterObservationFilter.MAX_PARAMETER_SETS);
        assertThat(sets.get(QueryParameterObservationFilter.MAX_PARAMETER_SETS - 1)
                        .get(0)
                        .asString(""))
                .isEqualTo(String.valueOf(QueryParameterObservationFilter.MAX_PARAMETER_SETS));
    }

    /** setNull's second argument is the SQL type, not a value. */
    @Test
    void rendersSetNullAsNull() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO person (id, name) VALUES (?, ?)")) {
            insert.setLong(1, 3);
            insert.setNull(2, Types.VARCHAR);
            insert.executeUpdate();
        }

        assertThat(parametersTag()).isEqualTo("[[\"3\",\"NULL\"]]");
    }

    @Test
    void addsNoKeyToAPlainStatement() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT COUNT(*) FROM person").close();
        }

        assertThat(queryContext().getHighCardinalityKeyValue(DbSpans.PARAMETERS_TAG))
                .isNull();
    }

    /** datasource-proxy still records one, empty, parameter set for it. */
    @Test
    void addsNoKeyToAPreparedStatementWithoutParameters() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM person")) {
            count.executeQuery().close();
        }

        assertThat(queryContext().getHighCardinalityKeyValue(DbSpans.PARAMETERS_TAG))
                .isNull();
    }

    /** A CallableStatement's named parameters and its out-parameter registrations are no placeholders' values. */
    @Test
    void skipsParametersSetByNameAndOutParameterRegistrations() throws NoSuchMethodException {
        Method byIndex = PreparedStatement.class.getMethod("setString", int.class, String.class);
        Method byName = CallableStatement.class.getMethod("setString", String.class, String.class);
        Method outParameter = CallableStatement.class.getMethod("registerOutParameter", int.class, int.class);
        QueryInfo call = new QueryInfo("{call rename(?, ?)}");
        call.getParametersList()
                .add(List.of(
                        new ParameterSetOperation(byName, new Object[] {"name", "Ann"}),
                        new ParameterSetOperation(outParameter, new Object[] {2, Types.VARCHAR}),
                        new ParameterSetOperation(byIndex, new Object[] {1, "Bob"})));
        QueryContext context = new QueryContext();
        context.setQueryInfoList(List.of(call));

        new QueryParameterObservationFilter().map(context);

        assertThat(context.getHighCardinalityKeyValue(DbSpans.PARAMETERS_TAG))
                .extracting(KeyValue::getValue)
                .isEqualTo("[[\"'Bob'\"]]");
    }

    /** A display-only capture must not fail the query it decorates; a driver-specific bind object's own toString() can throw. */
    @Test
    void addsNoTagWhenRenderingAParameterThrows() throws NoSuchMethodException {
        Method byIndex = PreparedStatement.class.getMethod("setObject", int.class, Object.class);
        Object poison = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        };
        QueryInfo query = new QueryInfo("SELECT 1");
        query.getParametersList().add(List.of(new ParameterSetOperation(byIndex, new Object[] {1, poison})));
        QueryContext context = new QueryContext();
        context.setQueryInfoList(List.of(query));

        new QueryParameterObservationFilter().map(context);

        assertThat(context.getHighCardinalityKeyValues()).isEmpty();
    }

    /** The listener sets the list before it starts the observation; a QueryContext built any other way may not have one. */
    @Test
    void leavesAQueryContextWithoutQueriesUntouched() {
        QueryContext context = new QueryContext();

        new QueryParameterObservationFilter().map(context);

        assertThat(context.getHighCardinalityKeyValues()).isEmpty();
    }

    @Test
    void leavesEveryOtherContextUntouched() {
        Observation.Context context = new Observation.Context();

        new QueryParameterObservationFilter().map(context);

        assertThat(context.getHighCardinalityKeyValues()).isEmpty();
    }

    private String parametersTag() {
        KeyValue tag = queryContext().getHighCardinalityKeyValue(DbSpans.PARAMETERS_TAG);
        assertThat(tag)
                .as("the key the tracing handler turns into the span tag")
                .isNotNull();
        return tag.getValue();
    }

    private QueryContext queryContext() {
        List<QueryContext> queries = stopped.stream()
                .filter(QueryContext.class::isInstance)
                .map(QueryContext.class::cast)
                .toList();
        assertThat(queries)
                .as("one statement ran, so one query observation stopped")
                .hasSize(1);
        return queries.getFirst();
    }
}
