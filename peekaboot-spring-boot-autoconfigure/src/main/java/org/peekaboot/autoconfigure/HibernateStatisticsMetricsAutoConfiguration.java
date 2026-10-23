package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;

/**
 * The Hibernate meters the insights panels sample, for an application without
 * hibernate-micrometer. With that jar present Boot's own binder registers the same meters, so
 * this one steps aside. Ordered after Boot's JPA and metrics auto-configurations, which define the
 * two beans it binds between.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnPeekabootServlet
@ConditionalOnBooleanProperty(name = PeekabootPropertyKeys.INSIGHTS_ENABLED, matchIfMissing = true)
@ConditionalOnClass({EntityManagerFactory.class, SessionFactory.class, MeterRegistry.class})
@ConditionalOnMissingClass(HibernateStatisticsMeterBinder.HIBERNATE_MICROMETER_BINDER)
@ConditionalOnBean({EntityManagerFactory.class, MeterRegistry.class})
public class HibernateStatisticsMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HibernateStatisticsMeterBinder hibernateStatisticsMeterBinder(
            ConfigurableListableBeanFactory beanFactory, MeterRegistry meterRegistry) {
        return new HibernateStatisticsMeterBinder(beanFactory, meterRegistry);
    }
}
