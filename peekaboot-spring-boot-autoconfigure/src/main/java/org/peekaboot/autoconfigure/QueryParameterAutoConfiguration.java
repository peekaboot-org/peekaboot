package org.peekaboot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Records every JDBC query's bind parameters on its span; Boot applies the filter bean to the registry itself. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "net.ttddyy.observation.tracing.QueryContext")
@ConditionalOnBooleanProperty(PeekabootPropertyKeys.ENABLED)
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.TRACING_ENABLED, matchIfMissing = true)
public class QueryParameterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QueryParameterObservationFilter queryParameterObservationFilter() {
        return new QueryParameterObservationFilter();
    }
}
