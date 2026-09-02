package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.mock.env.MockEnvironment;

class ApplicationReadyListenerTest {

    @Test
    void banner_linksTheDashboardBelowTheServiceUrlAndSwaggerUi() {
        String report = report(ReadyEvents.webApplicationServingDashboard(8083));

        assertThat(report)
                .containsSubsequence(" Service URL:", " Swagger UI:", " Peekaboot Dashboard:")
                .contains(" Peekaboot Dashboard: http://localhost:8083/peekaboot/");
    }

    @Test
    void banner_omitsTheDashboardWhenPeekabootDoesNotServeIt() {
        String report = report(ReadyEvents.webApplication(8083));

        assertThat(report).contains(" Service URL:").doesNotContain("Peekaboot Dashboard");
    }

    /** The banner is read by people in every locale; "1,5 MB" under a German default is not a number to a log parser. */
    @Test
    void banner_reportsMemoryInLocaleIndependentHumanUnits() {
        Locale defaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        try {
            String report = report(ReadyEvents.webApplication(8083));

            assertThat(report)
                    .containsPattern(" Heap Memory: used=\\d+(\\.\\d)? [KMG]?B, max=\\d+(\\.\\d)? [KMG]?B\n")
                    .containsPattern(" Non-Heap Memory: used=\\d+(\\.\\d)? [KMG]?B, max=\\d+(\\.\\d)? [KMG]?B\n");
        } finally {
            Locale.setDefault(defaultLocale);
        }
    }

    @Test
    void banner_masksSensitiveConnectionParams() {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn("primary");
        when(metadata.getConnectionParams())
                .thenReturn(new LinkedHashMap<>(Map.of(
                        "ssl", new JdbcProperty(PropertySource.QUERY, "true"),
                        "password", new JdbcProperty(PropertySource.QUERY, "s3cret"))));

        String report = report(ReadyEvents.webApplication(8083), List.of(metadata));

        assertThat(report)
                .contains(" DB Connection Params: ")
                .contains("ssl=true")
                .contains("password=******")
                .doesNotContain("s3cret");
    }

    @Test
    void banner_reportsThePoolSettingsOfAHikariDataSource() {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn("primary");
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setMinimumIdle(3);
            hikari.setMaximumPoolSize(7);
            hikari.setConnectionTimeout(2500);

            String report = report(
                    ReadyEvents.webApplication(8083),
                    List.of(metadata),
                    Map.of("primary", hikari),
                    new HikariPoolInfo());

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
    void banner_omitsThePoolLinesWithoutAPoolInfoContributor() {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn("primary");

        String report = report(
                ReadyEvents.webApplication(8083), List.of(metadata), Map.of("primary", mock(DataSource.class)), null);

        assertThat(report).contains(" DB Connection [primary]").doesNotContain("DB Pool");
    }

    private static String report(ApplicationReadyEvent event) {
        return report(event, List.of());
    }

    private static String report(ApplicationReadyEvent event, List<DataSourceMetadata> dataSources) {
        return report(event, dataSources, Map.of(), null);
    }

    /** Runs the listener against {@code event} and returns the single banner it logs. */
    private static String report(
            ApplicationReadyEvent event,
            List<DataSourceMetadata> metadata,
            Map<String, DataSource> dataSources,
            HikariPoolInfo hikariPoolInfo) {
        var listener = new ApplicationReadyListener(
                new EnvironmentInfo(new MockEnvironment()),
                new BuildInfoProvider(null),
                new ServerUrlResolver(new MockEnvironment(), () -> true),
                metadata,
                dataSources,
                hikariPoolInfo);

        try (var capture = LogCapture.attach(ApplicationReadyListener.class)) {
            listener.onApplicationEvent(event);

            assertThat(capture.appender().list).hasSize(1);
            return capture.appender().list.get(0).getFormattedMessage();
        }
    }
}
