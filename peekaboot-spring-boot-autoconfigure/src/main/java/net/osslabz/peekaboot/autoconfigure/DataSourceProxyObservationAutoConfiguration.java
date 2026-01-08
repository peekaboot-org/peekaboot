package net.osslabz.peekaboot.autoconfigure;

import io.micrometer.observation.ObservationRegistry;
import javax.sql.DataSource;
import net.ttddyy.observation.boot.autoconfigure.DataSourceNameResolver;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import net.ttddyy.observation.boot.autoconfigure.DefaultDataSourceNameResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;


@AutoConfiguration(before = DataSourceObservationAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, DataSourceObservationAutoConfiguration.class, ObservationRegistry.class})
public class DataSourceProxyObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSourceNameResolver observationDataSourceNameResolver() {

        return new DefaultDataSourceNameResolver();
    }
}