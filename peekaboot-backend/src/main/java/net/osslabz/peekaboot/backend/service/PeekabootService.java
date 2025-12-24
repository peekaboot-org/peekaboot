package net.osslabz.peekaboot.backend.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


@Service
public class PeekabootService {

    private static final Logger log = LoggerFactory.getLogger(PeekabootService.class);

    private final ApplicationContext applicationContext;

    private final UnfilteredEndpoint unfilteredEndpoint;


    public PeekabootService(ApplicationContext applicationContext, UnfilteredEndpoint unfilteredEndpoint) {

        this.applicationContext = applicationContext;
        this.unfilteredEndpoint = unfilteredEndpoint;
    }


    public Map<String, Object> getData() {

        Map<String, Object> data = new LinkedHashMap<>();
        applicationContext.getBeansOfType(InfoEndpoint.class)
            .forEach((beanName, endpoint) -> {
                String id = AnnotationUtils.findAnnotation(
                    AopUtils.getTargetClass(endpoint),
                    Endpoint.class
                ).id();
                data.put("%s-%s".formatted(id, beanName), endpoint.info());
            });

        applicationContext.getBeansOfType(HealthEndpoint.class)
            .forEach((beanName, endpoint) -> {
                String id = AnnotationUtils.findAnnotation(
                    AopUtils.getTargetClass(endpoint),
                    Endpoint.class
                ).id();
                data.put("%s-%s".formatted(id, beanName), endpoint.health());
            });

        return data;
    }


    public Map<String, Object> getDataGeneric() {

        return this.unfilteredEndpoint.getData();
    }

/*
    private Map<String, Map<String, Object>> getEnvironmentData() {

        Map<String, Map<String, Object>> propertySources = new LinkedHashMap<>();

        if (environment instanceof ConfigurableEnvironment configurableEnv) {
            configurableEnv.getPropertySources().forEach(propertySource -> {
                Map<String, Object> properties = extractProperties(propertySource);
                if (!properties.isEmpty()) {
                    propertySources.put(propertySource.getName(), properties);
                }

            });
        }

        return propertySources;
    }
*/


    private Map<String, Object> extractProperties(PropertySource<?> propertySource) {

        Map<String, Object> properties = new LinkedHashMap<>();

        if (propertySource instanceof EnumerablePropertySource<?> enumerableSource) {
            for (String propertyName : enumerableSource.getPropertyNames()) {
                Object value = enumerableSource.getProperty(propertyName);
                properties.put(propertyName, sanitizeValue(propertyName, value));

            }
        }

        return properties;
    }


    private boolean isSensitive(String key) {

        String lowerKey = key.toLowerCase();
        return lowerKey.contains("password") ||
            lowerKey.contains("secret") ||
            lowerKey.contains("token") ||
            lowerKey.contains("key") ||
            lowerKey.contains("credential");
    }


    private Object sanitizeValue(String key, Object value) {

        if (isSensitive(key)) {
            return "******";
        }
        return value;
    }


    @Scheduled(initialDelay = 10, fixedDelay = 100, timeUnit = TimeUnit.MINUTES)
    public void scheduledFixed() {

    }


    @Scheduled(cron = "0 0 1 ? * SAT")
    public void scheduledCron() {

    }

}