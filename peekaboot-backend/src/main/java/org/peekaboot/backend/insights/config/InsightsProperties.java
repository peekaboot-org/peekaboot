package org.peekaboot.backend.insights.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.insights")
public class InsightsProperties {

    /** Whether the collector, the /api/insights endpoints and the Insights tab exist at all; also needs a MeterRegistry bean. */
    private boolean enabled = true;

    /** The sampling tick (level 0) and each aggregation window above it; every interval must be a whole multiple of the previous one. */
    private List<Level> levels = defaultLevels();

    /** A Spring resource location for the panel file, replacing the default lookup of peekaboot-insights.yml on the classpath root. */
    private String configLocation;

    private Persistence persistence = new Persistence();

    private static List<Level> defaultLevels() {
        List<Level> defaults = new ArrayList<>();
        defaults.add(Level.of(Duration.ofSeconds(10), 90));
        defaults.add(Level.of(Duration.ofMinutes(1), 1440));
        defaults.add(Level.of(Duration.ofHours(1), 720));
        return defaults;
    }

    public void validate() {
        validateLevels();
        validatePersistence();
    }

    private void validateLevels() {
        if (levels == null || levels.isEmpty()) {
            throw new IllegalStateException("peekaboot.insights.levels must contain at least one level");
        }
        Level previous = null;
        for (Level level : levels) {
            if (level.interval == null || level.interval.isZero() || level.interval.isNegative()) {
                throw new IllegalStateException("peekaboot.insights.levels: interval must be positive");
            }
            if (level.size <= 0) {
                throw new IllegalStateException("peekaboot.insights.levels: size must be > 0");
            }
            if (previous != null) {
                validateRollUp(level, previous);
            }
            previous = level;
        }
    }

    /** A roll-up aggregates the previous ring, so its window must be whole entries of that ring and fit inside it. */
    private static void validateRollUp(Level level, Level previous) {
        if (level.interval.toMillis() % previous.interval.toMillis() != 0) {
            throw new IllegalStateException("peekaboot.insights.levels: each interval must be a"
                    + " multiple of the previous one (" + level.interval + " vs " + previous.interval + ")");
        }
        long entries = level.interval.toMillis() / previous.interval.toMillis();
        if (entries > previous.size) {
            throw new IllegalStateException("peekaboot.insights.levels: each interval must fit in the previous"
                    + " level's ring (" + level.interval + " spans " + entries + " entries of " + previous.interval
                    + ", but that ring holds " + previous.size + ")");
        }
    }

    private void validatePersistence() {
        if (persistence == null) {
            throw new IllegalStateException("peekaboot.insights.persistence must not be null");
        }
        if (persistence.interval != null && (persistence.interval.isZero() || persistence.interval.isNegative())) {
            throw new IllegalStateException("peekaboot.insights.persistence.interval must be positive");
        }
        if (persistence.maxAge != null && (persistence.maxAge.isZero() || persistence.maxAge.isNegative())) {
            throw new IllegalStateException("peekaboot.insights.persistence.max-age must be positive");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Level> getLevels() {
        return levels;
    }

    public void setLevels(List<Level> levels) {
        this.levels = levels;
    }

    public String getConfigLocation() {
        return configLocation;
    }

    public void setConfigLocation(String configLocation) {
        this.configLocation = configLocation;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }

    /** How often the rings are written, defaulting to one write per coarsest window. */
    public Duration resolvePersistenceInterval() {
        return persistence.getInterval() != null
                ? persistence.getInterval()
                : levels.get(levels.size() - 1).getInterval();
    }

    /**
     * How old a snapshot may be and still be worth loading, defaulting to the span the
     * coarsest level covers - beyond it, every restored sample would be an empty gap.
     */
    public Duration resolvePersistenceMaxAge() {
        if (persistence.getMaxAge() != null) {
            return persistence.getMaxAge();
        }
        Level coarsest = levels.get(levels.size() - 1);
        return coarsest.getInterval().multipliedBy(coarsest.getSize());
    }

    public static class Persistence {

        /** How often the rings are written to insights.snapshot; defaults to the coarsest level's interval. */
        private Duration interval;

        /** How old a snapshot may be and still be loaded; defaults to the coarsest level's span (interval x size). */
        private Duration maxAge;

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
    }

    public static class Level {

        /** The sampling tick for level 0, the aggregation window for every level above it. */
        private Duration interval;

        /** Ring buffer entries kept per series at this level; interval x size is how far back the charts reach. */
        private int size;

        public static Level of(Duration interval, int size) {
            Level level = new Level();
            level.interval = interval;
            level.size = size;
            return level;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public int getSize() {
            return size;
        }

        public void setSize(int size) {
            this.size = size;
        }
    }
}
