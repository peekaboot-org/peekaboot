package org.peekaboot.backend.lifecycle;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.peekaboot.backend.domain.runtime.ProcessInfo;
import org.peekaboot.backend.masking.ConnectionParamsMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.LOWEST_PRECEDENCE)
public class ApplicationReadyListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationReadyListener.class);

    private final EnvironmentInfo environmentInfo;

    private final BuildInfoProvider buildInfoProvider;

    private final ServerUrlResolver serverUrlResolver;

    private final List<DataSourceMetadata> dataSourceMetadataList;

    private final Map<String, DataSource> dataSources;

    private final ConnectionParamsMasker connectionParamsMasker = new ConnectionParamsMasker();

    public ApplicationReadyListener(
            EnvironmentInfo environmentInfo,
            BuildInfoProvider buildInfoProvider,
            ServerUrlResolver serverUrlResolver,
            List<DataSourceMetadata> dataSourceMetadataList,
            Map<String, DataSource> dataSources) {

        this.environmentInfo = environmentInfo;
        this.buildInfoProvider = buildInfoProvider;
        this.serverUrlResolver = serverUrlResolver;
        this.dataSourceMetadataList = dataSourceMetadataList != null ? dataSourceMetadataList : List.of();
        this.dataSources = dataSources;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {

        StringBuilder report = LifecycleBanner.open("ApplicationReady");

        appendApplicationInfo(report);
        appendServiceUrl(report, event);
        appendBuildInfo(report);
        appendSystemInfo(report);
        appendMemoryInfo(report);
        appendDatabaseInfo(report);

        LifecycleBanner.close(report);

        logger.info(report.toString());
    }

    private void appendApplicationInfo(StringBuilder report) {

        String appName = buildInfoProvider.getName();
        String profiles = environmentInfo.getActiveProfilesAsString();
        report.append(String.format(" Application [%s] ready with active profiles [%s]", appName, profiles))
                .append("\n");
        report.append(LifecycleBanner.LINE).append("\n");
    }

    private void appendServiceUrl(StringBuilder report, ApplicationReadyEvent event) {

        serverUrlResolver.resolveServiceUrl(event).ifPresent(url -> {
            report.append(" Service URL: ").append(url).append("\n");
            report.append(LifecycleBanner.LINE).append("\n");
        });

        serverUrlResolver.resolveSwaggerUiUrl(event).ifPresent(url -> {
            report.append(" Swagger UI: ").append(url).append("\n");
            report.append(LifecycleBanner.LINE).append("\n");
        });

        serverUrlResolver.resolveDashboardUrl(event).ifPresent(url -> {
            report.append(" Peekaboot Dashboard: ").append(url).append("\n");
            report.append(LifecycleBanner.LINE).append("\n");
        });
    }

    private void appendBuildInfo(StringBuilder report) {

        if (buildInfoProvider.isBuildInfoAvailable()) {
            report.append(" Application Info: ")
                    .append(buildInfoProvider.getFormattedInfo())
                    .append("\n");
        } else {
            report.append(" Application Info: Build information not available\n");
        }
        report.append(LifecycleBanner.LINE).append("\n");
    }

    private void appendSystemInfo(StringBuilder report) {

        report.append(" Default Timezone: ")
                .append(TimeZone.getDefault().getID())
                .append("\n");
        report.append(LifecycleBanner.LINE).append("\n");

        String vmName = System.getProperty("java.vm.name");
        report.append(" Java VM Name: ").append(vmName).append("\n");
        report.append(LifecycleBanner.LINE).append("\n");

        String vmVersion = System.getProperty("java.version");
        report.append(" Java VM Version: ").append(vmVersion).append("\n");
        report.append(LifecycleBanner.LINE).append("\n");

        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        report.append(String.format(" Operating System: %s %s (%s)", osName, osVersion, osArch))
                .append("\n");
        report.append(LifecycleBanner.LINE).append("\n");

        ProcessInfo processInfo = ProcessInfo.current();
        report.append(String.format(
                        " Process User: %s (uid=%s, gid=%s, pid=%d)",
                        processInfo.username(), processInfo.uid(), processInfo.gid(), processInfo.pid()))
                .append("\n");
        if (!processInfo.parentProcesses().isEmpty()) {
            String tree = processInfo.parentProcesses().stream()
                    .map(p -> p.command().isEmpty() ? String.valueOf(p.pid()) : p.command() + "(" + p.pid() + ")")
                    .reduce((a, b) -> a + " -> " + b)
                    .orElse("");
            report.append(" Process Tree: ").append(tree).append("\n");
        }
        report.append(LifecycleBanner.LINE).append("\n");
    }

    private void appendMemoryInfo(StringBuilder report) {

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();

        report.append(String.format(
                " Heap Memory: used=%s, max=%s\n",
                formatBytes(heapMemory.getUsed()), formatBytes(heapMemory.getMax())));
        report.append(String.format(
                " Non-Heap Memory: used=%s, max=%s\n",
                formatBytes(nonHeapMemory.getUsed()),
                formatBytes(nonHeapMemory.getMax() > 0 ? nonHeapMemory.getMax() : 0)));
        report.append(LifecycleBanner.LINE).append("\n");
    }

    private void appendDatabaseInfo(StringBuilder report) {

        if (dataSourceMetadataList.isEmpty()) {
            report.append(" Database: No database configured\n");
            report.append(LifecycleBanner.LINE).append("\n");
            return;
        }

        for (DataSourceMetadata metadata : dataSourceMetadataList) {
            report.append(String.format(
                    " DB Connection [%s]: %s on %s (user: %s)\n\n",
                    metadata.getDataSourceName(),
                    metadata.getDatabaseName(),
                    metadata.getHosts(),
                    metadata.getUsername()));

            if (!metadata.getConnectionParams().isEmpty()) {
                String params = connectionParamsMasker.mask(metadata.getConnectionParams()).entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                report.append(" DB Connection Params: ").append(params).append("\n");
                report.append(LifecycleBanner.LINE).append("\n");
            }

            report.append(String.format(
                    " DB Version: %s %s\n", metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion()));
            report.append(LifecycleBanner.LINE).append("\n");

            appendPoolInfo(report, metadata.getDataSourceName());
        }
    }

    // CloseResource: the DataSource is the application's own Spring bean, merely
    // borrowed here to report pool settings - closing it would break the app
    @SuppressWarnings("PMD.CloseResource")
    private void appendPoolInfo(StringBuilder report, String dataSourceName) {

        if (dataSources == null || !dataSources.containsKey(dataSourceName)) {
            return;
        }

        DataSource dataSource = dataSources.get(dataSourceName);
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            HikariConfigMXBean config = hikariDataSource.getHikariConfigMXBean();
            report.append(String.format(
                    " DB Pool: minimumIdle=%d, maximumPoolSize=%d\n\n",
                    config.getMinimumIdle(), config.getMaximumPoolSize()));

            report.append(" Connection Timeout: ")
                    .append(config.getConnectionTimeout())
                    .append(" ms\n");
            report.append(LifecycleBanner.LINE).append("\n");
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
