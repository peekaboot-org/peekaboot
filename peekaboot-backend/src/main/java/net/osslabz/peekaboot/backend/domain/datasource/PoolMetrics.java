package net.osslabz.peekaboot.backend.domain.datasource;

public record PoolMetrics(
    String poolType,
    int activeConnections,
    int idleConnections,
    int maxConnections,
    int minConnections,
    double usagePercent
) {
    public static PoolMetrics of(String poolType, int active, int idle, int max, int min) {
        double percent = max > 0 ? (double) active / max * 100.0 : 0.0;
        return new PoolMetrics(poolType, active, idle, max, min, Math.round(percent * 100.0) / 100.0);
    }
}
