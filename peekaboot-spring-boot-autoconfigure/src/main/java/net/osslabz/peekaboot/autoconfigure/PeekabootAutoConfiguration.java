package net.osslabz.peekaboot.autoconfigure;

import net.osslabz.peekaboot.backend.config.PeekabootWebConfig;
import net.osslabz.peekaboot.backend.service.PeekabookActuatorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.invoke.OperationInvokerAdvisor;
import org.springframework.boot.actuate.endpoint.invoke.ParameterValueMapper;
import org.springframework.boot.actuate.endpoint.web.AdditionalPathsMapper;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.PathMapper;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;


@AutoConfiguration
@ConditionalOnClass({HealthEndpoint.class, InfoEndpoint.class})
@ConditionalOnProperty(prefix = "peekaboot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PeekabootProperties.class)
@Import(PeekabootWebConfig.class)
public class PeekabootAutoConfiguration {


    @Bean
    @ConditionalOnMissingBean(PeekabookActuatorService.class)
    public PeekabookActuatorService peekabootActuatorService(ApplicationContext context,
        ParameterValueMapper parameterMapper,
        EndpointMediaTypes mediaTypes,
        ObjectProvider<PathMapper> pathMappers,
        ObjectProvider<AdditionalPathsMapper> additionalPathsMappers,
        ObjectProvider<OperationInvokerAdvisor> advisors) {

        return new PeekabookActuatorService(context, parameterMapper, mediaTypes, pathMappers, additionalPathsMappers, advisors);
    }
}