package net.osslabz.sandbox;

import io.micrometer.observation.ObservationRegistry;
import jakarta.activation.DataSource;
import net.ttddyy.observation.boot.autoconfigure.DataSourceNameResolver;
import net.ttddyy.observation.boot.autoconfigure.DataSourceObservationAutoConfiguration;
import net.ttddyy.observation.boot.autoconfigure.DefaultDataSourceNameResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;


@AutoConfiguration(before = DataSourceObservationAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, ObservationRegistry.class})
public class ProjectDataSourceObservationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSourceNameResolver observationDataSourceNameResolver() {

        return new DefaultDataSourceNameResolver();
    }
}