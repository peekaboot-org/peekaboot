package org.peekaboot.autoconfigure;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.SimpleAutowireCandidateResolver;
import org.springframework.util.StringUtils;

/**
 * Binds the four meters the insights panels read, {@code hibernate.transactions},
 * {@code hibernate.statements}, {@code hibernate.entities.loads} and {@code hibernate.sessions.open},
 * from each session factory's statistics. Names, tags, descriptions and counter types are
 * hibernate-micrometer's {@code HibernateMetrics} 7.4.5's, and the {@code entityManagerFactory} tag
 * is derived from the bean name as Boot's binder derives it, so a panel reads the same numbers
 * with either. Like {@code HibernateMetrics}, it binds nothing while statistics are off.
 */
final class HibernateStatisticsMeterBinder implements SmartInitializingSingleton {

    /** The binder Boot registers in this one's place where hibernate-micrometer is on the class path. */
    static final String HIBERNATE_MICROMETER_BINDER = "org.hibernate.orm.micrometer.HibernateMetrics";

    private static final String ENTITY_MANAGER_FACTORY_TAG = "entityManagerFactory";

    private static final String ENTITY_MANAGER_FACTORY_SUFFIX = "entityManagerFactory";

    private final Map<String, EntityManagerFactory> entityManagerFactories;

    private final MeterRegistry meterRegistry;

    HibernateStatisticsMeterBinder(ConfigurableListableBeanFactory beanFactory, MeterRegistry meterRegistry) {
        this.entityManagerFactories =
                SimpleAutowireCandidateResolver.resolveAutowireCandidates(beanFactory, EntityManagerFactory.class);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        entityManagerFactories.forEach(this::bind);
    }

    private void bind(String beanName, EntityManagerFactory entityManagerFactory) {
        Statistics statistics;
        try {
            statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        } catch (PersistenceException notHibernate) {
            return;
        }
        if (!statistics.isStatisticsEnabled()) {
            return;
        }
        Tags tags = Tags.of(ENTITY_MANAGER_FACTORY_TAG, entityManagerFactoryName(beanName));
        counter(statistics, "hibernate.sessions.open", "Sessions opened", Statistics::getSessionOpenCount, tags);
        counter(
                statistics,
                "hibernate.transactions",
                "The number of transactions we know to have been successful",
                Statistics::getSuccessfulTransactionCount,
                tags.and("result", "success"));
        counter(
                statistics,
                "hibernate.transactions",
                "The number of transactions we know to have failed",
                stats -> stats.getTransactionCount() - stats.getSuccessfulTransactionCount(),
                tags.and("result", "failure"));
        counter(
                statistics,
                "hibernate.statements",
                "The number of prepared statements that were acquired",
                Statistics::getPrepareStatementCount,
                tags.and("status", "prepared"));
        counter(
                statistics,
                "hibernate.statements",
                "The number of prepared statements that were released",
                Statistics::getCloseStatementCount,
                tags.and("status", "closed"));
        counter(
                statistics,
                "hibernate.entities.loads",
                "The number of entity loads",
                Statistics::getEntityLoadCount,
                tags);
    }

    // The statistics are held weakly; the session factory that owns them keeps them alive.
    private void counter(
            Statistics statistics, String name, String description, ToDoubleFunction<Statistics> count, Tags tags) {
        FunctionCounter.builder(name, statistics, count)
                .description(description)
                .tags(tags)
                .register(meterRegistry);
    }

    /** {@code entityManagerFactory} keeps its name, {@code secondaryEntityManagerFactory} becomes {@code secondary}, as in Boot. */
    private static String entityManagerFactoryName(String beanName) {
        if (beanName.length() > ENTITY_MANAGER_FACTORY_SUFFIX.length()
                && StringUtils.endsWithIgnoreCase(beanName, ENTITY_MANAGER_FACTORY_SUFFIX)) {
            return beanName.substring(0, beanName.length() - ENTITY_MANAGER_FACTORY_SUFFIX.length());
        }
        return beanName;
    }
}
