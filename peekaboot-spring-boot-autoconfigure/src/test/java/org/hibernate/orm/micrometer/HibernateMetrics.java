package org.hibernate.orm.micrometer;

/**
 * Stands in for hibernate-micrometer's binder, which this module does not depend on. Only its
 * presence matters: {@code HibernateStatisticsMetricsAutoConfiguration} steps aside where it is on
 * the class path, and the tests that need it absent filter it out.
 */
public final class HibernateMetrics {

    private HibernateMetrics() {}
}
