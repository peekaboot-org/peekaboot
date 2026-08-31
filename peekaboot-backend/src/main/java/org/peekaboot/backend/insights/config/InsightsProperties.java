package org.peekaboot.backend.insights.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "peekaboot.insights")
public class InsightsProperties {

    private boolean enabled = true;
    private List<Level> levels = defaultLevels();
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
        if (levels == null || levels.isEmpty()) {
            throw new IllegalStateException("peekaboot.insights.levels must contain at least one level");
        }
        Duration previous = null;
        for (Level level : levels) {
            if (level.interval == null || level.interval.isZero() || level.interval.isNegative()) {
                throw new IllegalStateException("peekaboot.insights.levels: interval must be positive");
            }
            if (level.size <= 0) {
                throw new IllegalStateException("peekaboot.insights.levels: size must be > 0");
            }
            if (previous != null && level.interval.toMillis() % previous.toMillis() != 0) {
                throw new IllegalStateException("peekaboot.insights.levels: each interval must be a"
                        + " multiple of the previous one (" + level.interval + " vs " + previous + ")");
            }
            previous = level.interval;
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

        private Duration interval;

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
        private Duration interval;
        private int size;

        static Level of(Duration interval, int size) {
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
