package net.osslabz.peekaboot.autoconfigure;

import com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceDecorator;
import com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceDecoratorAutoConfiguration;
import com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceNameResolver;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;


@AutoConfiguration(before = DataSourceDecoratorAutoConfiguration.class)
@ConditionalOnClass({DataSource.class, DataSourceDecoratorAutoConfiguration.class})
public class DataSourceProxyLoggingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSourceDecorator.class)
    public com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceNameResolver loggingDataSourceNameResolver(ApplicationContext applicationContext) {

        return new DataSourceNameResolver(applicationContext);
    }
}