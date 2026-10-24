package org.peekaboot.backend.lifecycle;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.config.ApplicationIdentity;
import org.peekaboot.backend.datasource.DataSourceMetadata;
import org.peekaboot.backend.datasource.HikariPoolInfo;
import org.peekaboot.backend.domain.runtime.ProcessInfo;
import org.peekaboot.backend.masking.ConnectionParamsMasker;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.text.BannerFrame;
import org.peekaboot.backend.text.ByteFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

// room for SecurityPostureListener, which reports below this banner at LOWEST_PRECEDENCE
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class ApplicationReadyListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReadyListener.class);

    private final ActiveProfilesLine activeProfilesLine;

    private final BuildInfoProvider buildInfoProvider;

    private final ApplicationIdentity applicationIdentity;

    private final ServerUrlResolver serverUrlResolver;

    private final List<DataSourceMetadata> dataSourceMetadataList;

    private final Map<String, DataSource> dataSources;

    /** Absent when HikariCP is not on the classpath; the banner then has no pool lines. */
    @Nullable
    private final HikariPoolInfo hikariPoolInfo;

    private final ConnectionParamsMasker connectionParamsMasker;

    private final ApplicationContext ownContext;

    public ApplicationReadyListener(
            ActiveProfilesLine activeProfilesLine,
            BuildInfoProvider buildInfoProvider,
            ApplicationIdentity applicationIdentity,
            ServerUrlResolver serverUrlResolver,
            List<DataSourceMetadata> dataSourceMetadataList,
            Map<String, DataSource> dataSources,
            @Nullable HikariPoolInfo hikariPoolInfo,
            MaskingEngine maskingEngine,
            ApplicationContext ownContext) {

        this.activeProfilesLine = activeProfilesLine;
        this.buildInfoProvider = buildInfoProvider;
        this.applicationIdentity = applicationIdentity;
        this.serverUrlResolver = serverUrlResolver;
        this.dataSourceMetadataList = dataSourceMetadataList;
        this.dataSources = dataSources;
        this.hikariPoolInfo = hikariPoolInfo;
        this.connectionParamsMasker = new ConnectionParamsMasker(maskingEngine);
        this.ownContext = ownContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!ContextEvents.fromOwnContext(event, ownContext)) {
            return;
        }

        StringBuilder report = BannerFrame.open("ApplicationReady");

        appendApplicationInfo(report);
        appendServiceUrl(report, event);
        appendBuildInfo(report);
        appendSystemInfo(report);
        appendMemoryInfo(report);
        appendDatabaseInfo(report);

        BannerFrame.close(report);

        log.info(report.toString());
    }

    private void appendApplicationInfo(StringBuilder report) {

        String appName = applicationIdentity.displayName();
        String profiles = activeProfilesLine.describe();
        BannerFrame.line(report, String.format(" Application [%s] ready with active profiles [%s]", appName, profiles));
    }

    private void appendServiceUrl(StringBuilder report, ApplicationReadyEvent event) {

        serverUrlResolver.resolveServiceUrl(event).ifPresent(url -> BannerFrame.line(report, " Service URL: " + url));
        serverUrlResolver.resolveSwaggerUiUrl(event).ifPresent(url -> BannerFrame.line(report, " Swagger UI: " + url));
        serverUrlResolver
                .resolveDashboardUrl(event)
                .ifPresent(url -> BannerFrame.line(report, " Peekaboot Dashboard: " + url));
    }

    private void appendBuildInfo(StringBuilder report) {

        BannerFrame.line(report, " Application Info: " + buildInfoProvider.getFormattedInfo());
    }

    private void appendSystemInfo(StringBuilder report) {

        BannerFrame.line(report, " Default Timezone: " + TimeZone.getDefault().getID());
        BannerFrame.line(report, " Java VM Name: " + System.getProperty("java.vm.name"));
        BannerFrame.line(report, " Java VM Version: " + System.getProperty("java.version"));

        String osName = System.getProperty("os.name");
        String osVersion = System.getProperty("os.version");
        String osArch = System.getProperty("os.arch");
        BannerFrame.line(report, String.format(" Operating System: %s %s (%s)", osName, osVersion, osArch));

        ProcessInfo processInfo = ProcessInfo.current();
        BannerFrame.text(
                report,
                String.format(
                        " Process User: %s (uid=%s, gid=%s, pid=%d)",
                        processInfo.username(), processInfo.uid(), processInfo.gid(), processInfo.pid()));
        if (!processInfo.parentProcesses().isEmpty()) {
            String tree = processInfo.parentProcesses().stream()
                    .map(p -> p.command().isEmpty() ? String.valueOf(p.pid()) : p.command() + "(" + p.pid() + ")")
                    .collect(Collectors.joining(" -> "));
            BannerFrame.text(report, " Process Tree: " + tree);
        }
        BannerFrame.rule(report);
    }

    private void appendMemoryInfo(StringBuilder report) {

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();

        BannerFrame.text(
                report,
                String.format(
                        " Heap Memory: used=%s, max=%s", ByteFormat.humanize(heapMemory.getUsed()), maxOf(heapMemory)));
        BannerFrame.line(
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
            BannerFrame.line(report, " Database: No database configured");
            return;
        }

        for (DataSourceMetadata metadata : dataSourceMetadataList) {
            BannerFrame.text(
                    report,
                    String.format(
                            " DB Connection [%s]: %s on %s (user: %s)",
                            metadata.dataSourceName(), metadata.databaseName(), metadata.hosts(), metadata.username()));
            BannerFrame.blankLine(report);

            if (!metadata.connectionParams().isEmpty()) {
                String params = connectionParamsMasker.mask(metadata.connectionParams()).entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining(", "));
                BannerFrame.line(report, " DB Connection Params: " + params);
            }

            BannerFrame.line(
                    report,
                    String.format(
                            " DB Version: %s %s", metadata.databaseProductName(), metadata.databaseProductVersion()));

            appendPoolInfo(report, metadata.dataSourceName());
        }
    }

    private void appendPoolInfo(StringBuilder report, String dataSourceName) {

        if (hikariPoolInfo == null || !dataSources.containsKey(dataSourceName)) {
            return;
        }

        DataSource dataSource = dataSources.get(dataSourceName);
        hikariPoolInfo.settingsOf(dataSource).ifPresent(settings -> {
            BannerFrame.text(
                    report,
                    String.format(
                            " DB Pool: minimumIdle=%d, maximumPoolSize=%d",
                            settings.minimumIdle(), settings.maximumPoolSize()));
            BannerFrame.blankLine(report);

            BannerFrame.line(report, " Connection Timeout: " + settings.connectionTimeoutMs() + " ms");
        });
    }
}
