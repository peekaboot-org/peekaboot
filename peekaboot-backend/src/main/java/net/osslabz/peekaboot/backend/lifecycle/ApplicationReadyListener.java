package net.osslabz.peekaboot.backend.lifecycle;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

@Order(Ordered.LOWEST_PRECEDENCE)
public class ApplicationReadyListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationReadyListener.class);
    private static final String SEPARATOR = "===========================================================================================";
    private static final String LINE = " ------------------------------------------------------------------------------------------";

    private final EnvironmentInfo environmentInfo;
    private final BuildInfoProvider buildInfoProvider;
    private final List<DatabaseMetadata> databaseMetadataList;
    private final Map<String, DataSource> dataSources;

    public ApplicationReadyListener(EnvironmentInfo environmentInfo,
                                   BuildInfoProvider buildInfoProvider,
                                   List<DatabaseMetadata> databaseMetadataList,
                                   Map<String, DataSource> dataSources) {
        this.environmentInfo = environmentInfo;
        this.buildInfoProvider = buildInfoProvider;
        this.databaseMetadataList = databaseMetadataList != null ? databaseMetadataList : List.of();
        this.dataSources = dataSources;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {

        StringBuilder report = new StringBuilder();
        report.append("\n").append(SEPARATOR).append("\n");
        report.append(" :: ApplicationReady :: \n");
        report.append(SEPARATOR).append("\n");

        appendApplicationInfo(report);
        appendBuildInfo(report);
        appendSystemInfo(report);
        appendMemoryInfo(report);
        appendDatabaseInfo(report);

        report.append(SEPARATOR);

        logger.info(report.toString());
    }

    private void appendApplicationInfo(StringBuilder report) {
        String appName = buildInfoProvider.getName();
        String profiles = environmentInfo.getActiveProfilesAsString();
        report.append(String.format(" Application [%s] ready with active profiles [%s]\n", appName, profiles));
        report.append(LINE).append("\n");
    }

    private void appendBuildInfo(StringBuilder report) {
        if (buildInfoProvider.isBuildInfoAvailable()) {
            report.append(" Application Info: ").append(buildInfoProvider.getFormattedInfo()).append("\n");
        } else {
            report.append(" Application Info: Build information not available\n");
        }
        report.append(LINE).append("\n");
    }

    private void appendSystemInfo(StringBuilder report) {
        report.append(" Default Timezone: ").append(TimeZone.getDefault().getID()).append("\n");
        report.append(LINE).append("\n");

        String vmName = System.getProperty("java.vm.name");
        report.append(" Java VM Name: ").append(vmName).append("\n");
        report.append(LINE).append("\n");

        String vmVersion = System.getProperty("java.version");
        report.append(" Java VM Version: ").append(vmVersion).append("\n");
        report.append(LINE).append("\n");

        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        report.append(String.format(" Operating System: %s %s (%s)\n", osName, osVersion, osArch));
        report.append(LINE).append("\n");
    }

    private void appendMemoryInfo(StringBuilder report) {
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();

        report.append(String.format(" Heap Memory: used=%s, max=%s\n",
                formatBytes(heapMemory.getUsed()),
                formatBytes(heapMemory.getMax())));
        report.append(String.format(" Non-Heap Memory: used=%s, max=%s\n",
                formatBytes(nonHeapMemory.getUsed()),
                formatBytes(nonHeapMemory.getMax() > 0 ? nonHeapMemory.getMax() : 0)));
        report.append(LINE).append("\n");
    }

    private void appendDatabaseInfo(StringBuilder report) {
        if (databaseMetadataList.isEmpty()) {
            report.append(" Database: No database configured\n");
            report.append(LINE).append("\n");
            return;
        }

        for (DatabaseMetadata metadata : databaseMetadataList) {
            report.append(String.format(" DB Connection [%s]: %s on %s:%d (user: %s)\n",
                    metadata.getDataSourceName(),
                    metadata.getDatabaseName(),
                    metadata.getHost(),
                    metadata.getPort(),
                    metadata.getUsername()));
            report.append(LINE).append("\n");

            if (!metadata.getConnectionParams().isEmpty()) {
                String params = metadata.getConnectionParams().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                report.append(" DB Connection Params: ").append(params).append("\n");
                report.append(LINE).append("\n");
            }

            report.append(String.format(" DB Version: %s %s\n",
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion()));
            report.append(LINE).append("\n");

            appendPoolInfo(report, metadata.getDataSourceName());
        }
    }

    private void appendPoolInfo(StringBuilder report, String dataSourceName) {
        if (dataSources == null || !dataSources.containsKey(dataSourceName)) {
            return;
        }

        DataSource dataSource = dataSources.get(dataSourceName);
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            HikariConfigMXBean config = hikariDataSource.getHikariConfigMXBean();
            report.append(String.format(" DB Pool: minimumIdle=%d, maximumPoolSize=%d\n",
                    config.getMinimumIdle(),
                    config.getMaximumPoolSize()));
            report.append(LINE).append("\n");

            try {
                String isolationLevel = hikariDataSource.getHikariConfigMXBean().getConnectionTimeout() > 0
                    ? "configured" : "default";
                report.append(" Connection Timeout: ").append(config.getConnectionTimeout()).append(" ms\n");
                report.append(LINE).append("\n");
            } catch (Exception e) {
                logger.debug("Could not retrieve connection timeout", e);
            }
        }
    }


    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}