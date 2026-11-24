package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.peekaboot.backend.datasource.ReportingDataSources.reporting;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.peekaboot.backend.config.ApplicationIdentity;
import org.peekaboot.backend.config.PeekabootPaths;
import org.peekaboot.backend.datasource.DataSourceMetadata;
import org.peekaboot.backend.datasource.DataSourceMetadataList;
import org.peekaboot.backend.datasource.HikariPoolInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.security.SecurityPosture;
import org.peekaboot.backend.security.SecurityPostureListener;
import org.peekaboot.backend.text.BannerFrame;
import org.peekaboot.testsupport.LogCapture;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

@Isolated("sets the JVM's default Locale")
class ApplicationReadyListenerTest {

    /** The security posture reports below this banner, an order an async multicaster's executor need not keep. */
    @Test
    void bannerIsLoggedAboveTheSecurityPostureUnderAnAsyncMulticaster() {
        ApplicationReadyEvent event = ReadyEvents.webApplication(8083);
        Deque<Runnable> held = new ArrayDeque<>();
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setTaskExecutor(held::push);
        multicaster.addApplicationListener(banner().listenerOf(event.getApplicationContext()));
        multicaster.addApplicationListener(
                new SecurityPostureListener(SecurityPosture.disabledOnADeployment("/peekaboot", false)));
        Logger postureLogger = (Logger) LoggerFactory.getLogger(SecurityPostureListener.class);
        try (LogCapture bannerCapture = LogCapture.attach(ApplicationReadyListener.class);
                LogCapture postureCapture = LogCapture.attach(SecurityPostureListener.class)) {
            // one list for both loggers, so it holds the two lines in the order they were logged
            postureLogger.addAppender(bannerCapture.appender());
            try {
                multicaster.multicastEvent(event);
                while (!held.isEmpty()) {
                    held.pop().run();
                }
            } finally {
                postureLogger.detachAppender(bannerCapture.appender());
            }

            assertThat(bannerCapture.appender().list)
                    .extracting(ILoggingEvent::getLoggerName)
                    .containsExactly(ApplicationReadyListener.class.getName(), SecurityPostureListener.class.getName());
            assertThat(postureCapture.appender().list).hasSize(1);
        }
    }

    /** The first line names the application and its profiles; the build line is the coordinates a deploy is checked against. */
    @Test
    void bannerNamesTheApplicationItsProfilesAndItsBuild() {
        Properties build = new Properties();
        build.setProperty("name", "orders");
        build.setProperty("group", "com.acme");
        build.setProperty("artifact", "orders-service");
        build.setProperty("version", "1.2.3");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod", "eu");

        BuildProperties buildProperties = new BuildProperties(build);
        String report = banner().environment(environment)
                .buildInfo(new BuildInfoProvider(buildProperties))
                .identity(new ApplicationIdentity(buildProperties, "orders-app"))
                .render(ReadyEvents.webApplication(8083));

        assertThat(report)
                .contains(" Application [orders] ready with active profiles [prod, eu]")
                .contains(" Application Info: com.acme:orders-service:1.2.3");
    }

    /** Without build-info on the classpath the banner says so instead of printing "unknown:unknown:unknown". */
    @Test
    void bannerSaysWhenBuildInfoIsUnavailable() {
        String report = banner().render(ReadyEvents.webApplication(8083));

        assertThat(report)
                .contains(" Application [unknown] ready with active profiles [default]")
                .contains(" Application Info: Build information not available");
    }

    /** Without build-info the application is still named, by spring.application.name. */
    @Test
    void bannerNamesTheApplicationWithoutBuildInfo() {
        String report =
                banner().identity(new ApplicationIdentity(null, "orders")).render(ReadyEvents.webApplication(8083));

        assertThat(report)
                .contains(" Application [orders] ready with active profiles [default]")
                .contains(" Application Info: Build information not available");
    }

    @Test
    void bannerLinksTheDashboardBelowTheServiceUrlAndSwaggerUi() {
        String report = banner().render(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(report)
                .containsSubsequence(" Service URL:", " Swagger UI:", " Peekaboot Dashboard:")
                .contains(" Peekaboot Dashboard: http://localhost:8083/peekaboot/");
    }

    @Test
    void bannerOmitsTheDashboardWhenPeekabootDoesNotServeIt() {
        String report = banner().render(ReadyEvents.webApplication(8083));

        assertThat(report).contains(" Service URL:").doesNotContain("Peekaboot Dashboard");
    }

    /**
     * The banner is read by people in every locale; "1,5 MB" under a German default is not a
     * number to a log parser. A pool without a configured maximum (HotSpot's non-heap by
     * default) says so rather than printing a limit of zero.
     *
     * <p>{@code Locale.setDefault} is JVM-global. The class is {@code @Isolated} for it;
     * {@code TestSourceConventionsTest} holds every such writer to that. The default is
     * restored in {@code finally}; see TESTING.md.
     */
    @Test
    void bannerReportsMemoryInLocaleIndependentHumanUnits() {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            String report = banner().render(ReadyEvents.webApplication(8083));

            assertThat(report)
                    .containsPattern(" Heap Memory: used=\\d+(\\.\\d)? [KMG]?B, max=[1-9]\\d*(\\.\\d)? [KMG]?B\n")
                    .containsPattern(
                            " Non-Heap Memory: used=\\d+(\\.\\d)? [KMG]?B, max=(unbounded|[1-9]\\d*(\\.\\d)? [KMG]?B)\n");
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    /**
     * Built by hand rather than read off H2: the driver reports its in-memory URL without
     * any credential parameter, so no live DataSource in this module can yield one.
     */
    @Test
    void bannerMasksSensitiveConnectionParams() {
        DataSourceMetadata metadata = new DataSourceMetadata(
                "primary",
                "app",
                List.of(),
                "orders",
                DatabaseProduct.POSTGRESQL,
                new LinkedHashMap<>(Map.of(
                        "ssl", new JdbcProperty(PropertySource.QUERY, "true"),
                        "password", new JdbcProperty(PropertySource.QUERY, "s3cret"))),
                "PostgreSQL",
                "16",
                "PostgreSQL JDBC Driver");

        String report = banner().metadata(metadata).render(ReadyEvents.webApplication(8083));

        assertThat(report)
                .containsPattern(" DB Connection Params: (ssl=true, password=\\*{6}|password=\\*{6}, ssl=true)\n")
                .doesNotContain("s3cret");
    }

    /** A hidden database prints as "-", the dashboard's placeholder. */
    @Test
    void bannerShowsNoCredentialOfTheJdbcUrl() throws SQLException {
        String report = banner().metadata(
                        DataSourceMetadata.read("primary", reporting("jdbc:mysql://ZUSR:S3Cx@db:3306/shop")),
                        DataSourceMetadata.read("legacy", reporting("jdbc:mysql://db/x;password=S3Cx")))
                .render(ReadyEvents.webApplication(8083));

        assertThat(report)
                .doesNotContain("ZUSR")
                .doesNotContain("S3C")
                .contains(" DB Connection [legacy]: - on [] (user: app)");
    }

    @Test
    void bannerNamesAnOracleInstanceWithItsHost() throws SQLException {
        String report = banner().metadata(
                        DataSourceMetadata.read("primary", reporting("jdbc:oracle:thin:@//db:1521/svc/inst")))
                .render(ReadyEvents.webApplication(8083));

        assertThat(report).contains(" DB Connection [primary]: svc on [db\\inst:1521]");
    }

    @Test
    void bannerReportsThePoolSettingsOfAHikariDataSource() throws SQLException {
        DataSourceMetadata metadata = h2Metadata("primary");
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setMinimumIdle(3);
            hikari.setMaximumPoolSize(7);
            hikari.setConnectionTimeout(2500);

            String report = banner().metadata(metadata)
                    .dataSources(Map.of("primary", hikari))
                    .hikari(new HikariPoolInfo())
                    .render(ReadyEvents.webApplication(8083));

            assertThat(report)
                    .contains(" DB Pool: minimumIdle=3, maximumPoolSize=7")
                    .contains(" Connection Timeout: 2500 ms");
        }
    }

    /**
     * Without HikariCP on the classpath there is no contributor at all - the banner must
     * simply skip the pool lines, not fail the ApplicationReadyEvent.
     */
    @Test
    void bannerOmitsThePoolLinesWithoutAPoolInfoContributor() throws SQLException {
        DataSourceMetadata metadata = h2Metadata("primary");

        String report = banner().metadata(metadata)
                .dataSources(Map.of("primary", mock(DataSource.class)))
                .render(ReadyEvents.webApplication(8083));

        assertThat(report).contains(" DB Connection [primary]").doesNotContain("DB Pool");
    }

    /** A database that is down is still a database: its line says so instead of "No database configured". */
    @Test
    void bannerNamesADataSourceThatHasNotAnswered() throws SQLException {
        DataSource down = mock(DataSource.class);
        when(down.getConnection()).thenThrow(new SQLException("db down"));
        try (LogCapture ignored = LogCapture.attach(DataSourceMetadataList.class)) {
            String report = banner().metadataList(DataSourceMetadataList.resolving(Map.of("primary", down)))
                    .render(ReadyEvents.webApplication(8083));

            assertThat(report)
                    .contains(" DB Connection [primary]: no connection yet")
                    .doesNotContain("No database configured");
        }
    }

    /** The pool's sizing is configuration, known without a connection. */
    @Test
    void bannerStillReportsThePoolOfADataSourceThatHasNotAnswered() {
        try (HikariDataSource hikari = new UnreachableHikariDataSource();
                LogCapture ignored = LogCapture.attach(DataSourceMetadataList.class)) {
            hikari.setMinimumIdle(3);
            hikari.setMaximumPoolSize(7);

            String report = banner().metadataList(DataSourceMetadataList.resolving(Map.of("primary", hikari)))
                    .dataSources(Map.of("primary", hikari))
                    .hikari(new HikariPoolInfo())
                    .render(ReadyEvents.webApplication(8083));

            assertThat(report)
                    .contains(" DB Connection [primary]: no connection yet")
                    .contains(" DB Pool: minimumIdle=3, maximumPoolSize=7");
        }
    }

    /**
     * Which lines carry a rule below them, and where the frame holds a blank line, reduced to each
     * line's label. The process tree depends on how the test JVM was launched, so it is left out.
     */
    @Test
    void bannerFramesItsLinesInGroups() {
        DataSourceMetadata metadata = new DataSourceMetadata(
                "primary",
                "app",
                List.of(),
                "orders",
                DatabaseProduct.POSTGRESQL,
                new LinkedHashMap<>(Map.of("ssl", new JdbcProperty(PropertySource.QUERY, "true"))),
                "PostgreSQL",
                "16",
                "PostgreSQL JDBC Driver");
        try (HikariDataSource hikari = new HikariDataSource()) {
            String report = banner().metadata(metadata)
                    .dataSources(Map.of("primary", hikari))
                    .hikari(new HikariPoolInfo())
                    .render(ReadyEvents.webApplication(8083));

            assertThat(skeleton(report))
                    .containsExactly(
                            "",
                            "=",
                            " :: ApplicationReady :: ",
                            "=",
                            "Application",
                            "-",
                            "Service URL",
                            "-",
                            "Swagger UI",
                            "-",
                            "Application Info",
                            "-",
                            "Default Timezone",
                            "-",
                            "Java VM Name",
                            "-",
                            "Java VM Version",
                            "-",
                            "Operating System",
                            "-",
                            "Process User",
                            "-",
                            "Heap Memory",
                            "Non-Heap Memory",
                            "-",
                            "DB Connection",
                            "",
                            "DB Connection Params",
                            "-",
                            "DB Version",
                            "-",
                            "DB Pool",
                            "",
                            "Connection Timeout",
                            "-",
                            "=");
        }
    }

    /**
     * In a parent/child hierarchy the child's ready event is published to the parent too; the
     * parent has logged its own banner already and must not log a second one for the child.
     */
    @Test
    void bannerIsNotLoggedForAnotherContextsReadyEvent() {
        ApplicationReadyListener listener = banner().listenerOf(mock(ApplicationContext.class));

        try (var capture = LogCapture.attach(ApplicationReadyListener.class)) {
            listener.onApplicationEvent(ReadyEvents.webApplication(8083));

            assertThat(capture.appender().list).isEmpty();
        }
    }

    /** The metadata a real in-memory H2 reports for {@code name}; the banner reads only the name off it. */
    private static DataSourceMetadata h2Metadata(String name) throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:banner-" + name + ";DB_CLOSE_DELAY=-1");
        return DataSourceMetadata.read(name, dataSource);
    }

    private static Banner banner() {
        return new Banner();
    }

    /** Fails before Hikari starts a pool, so the pool itself logs nothing. */
    private static final class UnreachableHikariDataSource extends HikariDataSource {

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("db down");
        }
    }

    /** The listener's collaborators, neutral unless a test sets one: no profiles, no build info, no databases. */
    private static final class Banner {
        private Environment environment = new MockEnvironment();
        private BuildInfoProvider buildInfo = new BuildInfoProvider(null);
        private ApplicationIdentity identity = new ApplicationIdentity(null, null);
        private DataSourceMetadataList metadata = DataSourceMetadataList.EMPTY;
        private Map<String, DataSource> dataSources = Map.of();
        private HikariPoolInfo hikariPoolInfo;

        Banner environment(Environment environment) {
            this.environment = environment;
            return this;
        }

        Banner buildInfo(BuildInfoProvider buildInfo) {
            this.buildInfo = buildInfo;
            return this;
        }

        Banner identity(ApplicationIdentity identity) {
            this.identity = identity;
            return this;
        }

        Banner metadata(DataSourceMetadata... metadata) {
            this.metadata = new DataSourceMetadataList(List.of(metadata));
            return this;
        }

        Banner metadataList(DataSourceMetadataList metadataList) {
            this.metadata = metadataList;
            return this;
        }

        Banner dataSources(Map<String, DataSource> dataSources) {
            this.dataSources = dataSources;
            return this;
        }

        Banner hikari(HikariPoolInfo hikariPoolInfo) {
            this.hikariPoolInfo = hikariPoolInfo;
            return this;
        }

        /** Runs the listener of {@code event}'s own context against it and returns the single banner it logs. */
        String render(ApplicationReadyEvent event) {
            try (var capture = LogCapture.attach(ApplicationReadyListener.class)) {
                listenerOf(event.getApplicationContext()).onApplicationEvent(event);

                return assertThat(capture.appender().list)
                        .singleElement()
                        .extracting(ILoggingEvent::getFormattedMessage)
                        .actual();
            }
        }

        ApplicationReadyListener listenerOf(ApplicationContext ownContext) {
            return new ApplicationReadyListener(
                    new ActiveProfilesLine(environment),
                    buildInfo,
                    identity,
                    new ServerUrlResolver(
                            new MockEnvironment(),
                            new StaticListableBeanFactory().getBeanProvider(PeekabootPaths.class),
                            () -> true),
                    metadata,
                    dataSources,
                    hikariPoolInfo,
                    new MaskingEngine(),
                    ownContext);
        }
    }

    /** Each line as its label: {@code =} for the frame, {@code -} for a rule, the title as is, blank as blank. */
    private static List<String> skeleton(String report) {
        return Stream.of(report.split("\n", -1))
                .filter(line -> !line.startsWith(" Process Tree: "))
                .map(line -> {
                    if (line.equals(BannerFrame.SEPARATOR)) {
                        return "=";
                    }
                    if (line.equals(BannerFrame.LINE)) {
                        return "-";
                    }
                    return line.startsWith(" ::")
                            ? line
                            : line.replaceAll("[:\\[].*", "").trim();
                })
                .toList();
    }
}
