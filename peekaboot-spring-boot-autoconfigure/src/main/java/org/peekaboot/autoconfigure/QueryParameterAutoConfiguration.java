package org.peekaboot.autoconfigure;

import org.peekaboot.backend.tracing.query.QueryParameterObservationFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Records every JDBC query's bind parameters on its span; Boot applies the filter bean to the registry itself. */
@AutoConfiguration
@ConditionalOnPeekabootTracing
@ConditionalOnClass(name = "net.ttddyy.observation.tracing.QueryContext")
public class QueryParameterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QueryParameterObservationFilter queryParameterObservationFilter() {
        return new QueryParameterObservationFilter();
    }
}
