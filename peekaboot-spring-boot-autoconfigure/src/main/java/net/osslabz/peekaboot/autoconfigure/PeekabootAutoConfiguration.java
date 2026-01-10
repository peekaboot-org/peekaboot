package net.osslabz.peekaboot.autoconfigure;

import java.util.List;

import net.osslabz.peekaboot.backend.config.PeekabootProperties;
import net.osslabz.peekaboot.backend.config.PeekabootWebConfig;
import net.osslabz.peekaboot.backend.controller.PeekabootController;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;


@AutoConfiguration(afterName = "net.osslabz.peekaboot.tracing.autoconfigure.PeekabootTracingAutoConfiguration")
@ConditionalOnClass({HealthEndpoint.class, InfoEndpoint.class})
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PeekabootProperties.class)
@Import({PeekabootWebConfig.class, PeekabootController.class})
public class PeekabootAutoConfiguration {


    @Bean
    @ConditionalOnMissingBean(PeekabookActuatorService.class)
    public PeekabookActuatorService peekabootActuatorService(ApplicationContext context,
        ParameterValueMapper parameterMapper,
        EndpointMediaTypes mediaTypes,
        ObjectProvider<PathMapper> pathMappers,
        ObjectProvider<AdditionalPathsMapper> additionalPathsMappers,
        ObjectProvider<OperationInvokerAdvisor> advisors,
        ObjectProvider<List<DataSourceMetadata>> dataSourceMetadataListProvider) {

        return new PeekabookActuatorService(context, parameterMapper, mediaTypes, pathMappers, additionalPathsMappers,
            advisors, dataSourceMetadataListProvider);
    }
}