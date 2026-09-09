package org.peekaboot.backend.lifecycle;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.runtime.ProcessInfo;
import org.peekaboot.backend.masking.ConnectionParamsMasker;
import org.peekaboot.backend.masking.MaskingEngine;
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

    /** Absent when HikariCP is not on the classpath; the banner then has no pool lines. */
    @Nullable
    private final HikariPoolInfo hikariPoolInfo;

    // Own MaskingEngine instance rather than the shared bean: the lifecycle
    // auto-configuration also runs in non-web contexts, where the bean does not exist.
    private final ConnectionParamsMasker connectionParamsMasker = new ConnectionParamsMasker(new MaskingEngine());

    public ApplicationReadyListener(
            EnvironmentInfo environmentInfo,
            BuildInfoProvider buildInfoProvider,
            ServerUrlResolver serverUrlResolver,
            List<DataSourceMetadata> dataSourceMetadataList,
            Map<String, DataSource> dataSources,
            @Nullable HikariPoolInfo hikariPoolInfo) {

        this.environmentInfo = environmentInfo;
        this.buildInfoProvider = buildInfoProvider;
        this.serverUrlResolver = serverUrlResolver;
        this.dataSourceMetadataList = dataSourceMetadataList;
        this.dataSources = dataSources;
        this.hikariPoolInfo = hikariPoolInfo;
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
        LifecycleBanner.line(
                report, String.format(" Application [%s] ready with active profiles [%s]", appName, profiles));
    }

    private void appendServiceUrl(StringBuilder report, ApplicationReadyEvent event) {

        serverUrlResolver
                .resolveServiceUrl(event)
                .ifPresent(url -> LifecycleBanner.line(report, " Service URL: " + url));
        serverUrlResolver
                .resolveSwaggerUiUrl(event)
                .ifPresent(url -> LifecycleBanner.line(report, " Swagger UI: " + url));
        serverUrlResolver
                .resolveDashboardUrl(event)
                .ifPresent(url -> LifecycleBanner.line(report, " Peekaboot Dashboard: " + url));
    }

    private void appendBuildInfo(StringBuilder report) {

        String info = buildInfoProvider.isBuildInfoAvailable()
                ? buildInfoProvider.getFormattedInfo()
                : "Build information not available";
        LifecycleBanner.line(report, " Application Info: " + info);
    }

    private void appendSystemInfo(StringBuilder report) {

        LifecycleBanner.line(
                report, " Default Timezone: " + TimeZone.getDefault().getID());
        LifecycleBanner.line(report, " Java VM Name: " + System.getProperty("java.vm.name"));
        LifecycleBanner.line(report, " Java VM Version: " + System.getProperty("java.version"));

        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        LifecycleBanner.line(report, String.format(" Operating System: %s %s (%s)", osName, osVersion, osArch));

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
                        " Heap Memory: used=%s, max=%s", ByteFormat.humanize(heapMemory.getUsed()), maxOf(heapMemory)))
                .append("\n");
        LifecycleBanner.line(
                report,
                String.format(
                        " Non-Heap Memory: used=%s, max=%s",
                        ByteFormat.humanize(nonHeapMemory.getUsed()), maxOf(nonHeapMemory)));
    }

    /** A pool without a configured maximum (HotSpot's non-heap by default) reports -1, not a limit of zero. */
    private static String maxOf(MemoryUsage usage) {
        return usage.getMax() < 0 ? "unbounded" : ByteFormat.humanize(usage.getMax());
    }

    private void appendDatabaseInfo(StringBuilder report) {

        if (dataSourceMetadataList.isEmpty()) {
            LifecycleBanner.line(report, " Database: No database configured");
            return;
        }

        for (DataSourceMetadata metadata : dataSourceMetadataList) {
            report.append(String.format(
                            " DB Connection [%s]: %s on %s (user: %s)",
                            metadata.getDataSourceName(),
                            metadata.getDatabaseName(),
                            metadata.getHosts(),
                            metadata.getUsername()))
                    .append("\n\n");

            if (!metadata.getConnectionParams().isEmpty()) {
                String params = connectionParamsMasker.mask(metadata.getConnectionParams()).entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                LifecycleBanner.line(report, " DB Connection Params: " + params);
            }

            LifecycleBanner.line(
                    report,
                    String.format(
                            " DB Version: %s %s",
                            metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion()));

            appendPoolInfo(report, metadata.getDataSourceName());
        }
    }

    private void appendPoolInfo(StringBuilder report, String dataSourceName) {

        if (hikariPoolInfo == null || !dataSources.containsKey(dataSourceName)) {
            return;
        }

        DataSource dataSource = dataSources.get(dataSourceName);
        hikariPoolInfo.settingsOf(dataSource).ifPresent(settings -> {
            report.append(String.format(
                            " DB Pool: minimumIdle=%d, maximumPoolSize=%d",
                            settings.minimumIdle(), settings.maximumPoolSize()))
                    .append("\n\n");

            LifecycleBanner.line(report, " Connection Timeout: " + settings.connectionTimeoutMs() + " ms");
        });
    }
}
