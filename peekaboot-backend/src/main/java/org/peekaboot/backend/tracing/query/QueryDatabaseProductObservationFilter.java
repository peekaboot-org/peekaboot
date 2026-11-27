package org.peekaboot.backend.tracing.query;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcUrlParser;
import net.ttddyy.observation.tracing.QueryContext;
import org.peekaboot.backend.tracing.tags.DatabaseProductTag;

/**
 * Names a query's database product as {@link DatabaseProductTag#KEY}, because datasource-micrometer's conventions
 * name few products and Peekaboot's own matches the DataSource card. High-cardinality because
 * {@code DefaultMeterObservationHandler} copies low-cardinality keys onto the {@code jdbc.query} timer.
 */
public final class QueryDatabaseProductObservationFilter implements ObservationFilter {

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof QueryContext query) {
            DatabaseProduct product = JdbcUrlParser.detectDatabaseProduct(query.getUrl());
            if (product != DatabaseProduct.UNKNOWN) {
                context.addHighCardinalityKeyValue(KeyValue.of(DatabaseProductTag.KEY, product.name()));
            }
        }
        return context;
    }
}
