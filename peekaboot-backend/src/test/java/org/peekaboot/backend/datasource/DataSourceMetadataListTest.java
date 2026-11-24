package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.testsupport.Latches;
import org.peekaboot.testsupport.LogCapture;

class DataSourceMetadataListTest {

    private static final Executor INLINE = Runnable::run;

    private static final Executor ON_A_VIRTUAL_THREAD = Thread::startVirtualThread;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-11-13T08:00:00Z"));

    /** A bean shared by the banner and the dashboard must not change under either of them. */
    @Test
    void entriesAreACopyTheCallerCannotAlter() {
        DataSourceMetadata metadata = metadata("primary");
        List<DataSourceMetadata> source = new ArrayList<>(List.of(metadata));

        DataSourceMetadataList list = new DataSourceMetadataList(source);
        source.clear();

        assertThat(list.entries()).containsExactly(metadata);
        assertThatThrownBy(() -> list.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void statedEntriesNameTheirDataSources() {
        DataSourceMetadataList list = new DataSourceMetadataList(List.of(metadata("primary")));

        assertThat(list.dataSourceNames()).containsExactly("primary");
    }

    /** The list is built while the context refreshes, where a database that is down would hold the start. */
    @Test
    void buildingTheListOpensNoConnection() {
        DataSource b = mock(DataSource.class);
        DataSource a = mock(DataSource.class);

        DataSourceMetadataList list = new DataSourceMetadataList(inBeanOrder("b", b, "a", a), clock, INLINE);

        assertThat(list.dataSourceNames()).containsExactly("b", "a");
        verifyNoInteractions(b, a);
    }

    @Test
    void theFirstReadResolvesEveryDataSourceInBeanOrder() {
        DataSourceMetadataList list =
                new DataSourceMetadataList(inBeanOrder("b", h2("b"), "a", h2("a")), clock, INLINE);

        assertThat(list.entries())
                .extracting(DataSourceMetadata::dataSourceName)
                .containsExactly("b", "a");
    }

    @Test
    void aDataSourceIsReadOnce() throws SQLException {
        DataSource dataSource = answering();
        DataSourceMetadataList list = new DataSourceMetadataList(Map.of("primary", dataSource), clock, INLINE);

        list.entries();
        list.entries();

        assertThat(list.entries()).hasSize(1);
        verify(dataSource, times(1)).getConnection();
    }

    /** A database that stays down is asked once per delay, however often the dashboard polls. */
    @Test
    void aFailedReadIsNotRepeatedBeforeTheRetryDelay() throws SQLException {
        DataSource dataSource = downOnceThenUp();
        try (LogCapture ignored = LogCapture.attach(DataSourceMetadataList.class)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("primary", dataSource), clock, INLINE);

            assertThat(list.entries()).isEmpty();
            clock.advance(Duration.ofSeconds(29));

            assertThat(list.entries()).isEmpty();
            verify(dataSource, times(1)).getConnection();
        }
    }

    /** A database that comes up after the application gets its card without a restart. */
    @Test
    void aFailedReadIsRepeatedOnceTheRetryDelayHasPassed() throws SQLException {
        DataSource dataSource = downOnceThenUp();
        try (LogCapture ignored = LogCapture.attach(DataSourceMetadataList.class)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("primary", dataSource), clock, INLINE);

            assertThat(list.entries()).isEmpty();
            clock.advance(DataSourceMetadataList.RETRY_AFTER);

            assertThat(list.entries()).hasSize(1);
            verify(dataSource, times(2)).getConnection();
        }
    }

    @Test
    void onlyTheFirstFailureOfADataSourceWarns() throws SQLException {
        DataSource dataSource = failing(new SQLException("db down"));
        try (LogCapture capture = LogCapture.attach(DataSourceMetadataList.class, Level.DEBUG)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("broken", dataSource), clock, INLINE);

            assertThat(list.entries()).isEmpty();
            clock.advance(DataSourceMetadataList.RETRY_AFTER);
            assertThat(list.entries()).isEmpty();

            assertThat(capture.appender().list)
                    .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            tuple(Level.WARN, "Failed to extract metadata from DataSource 'broken': db down"),
                            tuple(Level.DEBUG, "Failed to extract metadata from DataSource 'broken' again: db down"));
        }
    }

    /** JdbcUrlParser choking on an exotic URL, for one. */
    @Test
    void aReadThatThrowsUncheckedIsReportedLikeAnyFailure() throws SQLException {
        DataSource dataSource = failing(new IllegalStateException("unparseable"));
        try (LogCapture capture = LogCapture.attach(DataSourceMetadataList.class)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("exotic", dataSource), clock, INLINE);

            assertThat(list.entries()).isEmpty();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to extract metadata from DataSource 'exotic': unparseable");
            });
        }
    }

    /** The parser's message once carried the whole URL, password included, into this WARN line. */
    @Test
    void aUrlTheParserRejectsIsReportedWithoutItsText() throws SQLException {
        DataSource dataSource = ReportingDataSources.reporting("jdbc:db2://ZUSR:S3Cx@db:50000/shop");
        try (LogCapture capture = LogCapture.attach(DataSourceMetadataList.class)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("legacy", dataSource), clock, INLINE);
            assertThat(list.entries()).isEmpty();
            assertThat(capture.appender().list)
                    .singleElement()
                    .satisfies(
                            event -> assertThat(event.getFormattedMessage())
                                    .isEqualTo(
                                            "Failed to extract metadata from DataSource 'legacy': Unknown or unsupported database type"));
        }
    }

    /** A driver missing a class or a native library dies with an Error, which must not leave the read in flight for good. */
    @Test
    void anErrorFromTheDriverIsReportedLikeAnyFailure() throws SQLException {
        DataSource dataSource = failing(new LinkageError("no driver"));
        try (LogCapture capture = LogCapture.attach(DataSourceMetadataList.class)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("broken", dataSource), clock, INLINE);

            assertThat(list.entries()).isEmpty();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).endsWith("'broken': no driver");
            });

            clock.advance(DataSourceMetadataList.RETRY_AFTER);
            list.entries();

            verify(dataSource, times(2)).getConnection();
        }
    }

    @Test
    void aBoundedReadReturnsOnceEveryReadHasFinished() throws SQLException {
        DataSource dataSource = failing(new SQLException("db down"));
        try (LogCapture ignored = LogCapture.attach(DataSourceMetadataList.class)) {
            DataSourceMetadataList list = new DataSourceMetadataList(Map.of("broken", dataSource), clock, INLINE);

            assertThat(list.entriesWithin(Duration.ofDays(1))).isEmpty();
        }
    }

    /** A DataSource whose getConnection() hangs holds neither a request nor the DataSources beside it. */
    @Test
    void aReadInFlightNeverBlocksAReader() throws SQLException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean slowHasAnswered = new AtomicBoolean();
        AtomicInteger submissions = new AtomicInteger();
        Executor countingReader = read -> {
            submissions.incrementAndGet();
            Thread.startVirtualThread(read);
        };
        DataSourceMetadataList list = new DataSourceMetadataList(
                inBeanOrder("slow", answeringAfter(release, slowHasAnswered), "fast", h2("fast")),
                clock,
                countingReader);
        try {
            list.entries();

            assertThat(slowHasAnswered).isFalse();
            assertThat(submissions).hasValue(2);
            await().untilAsserted(() -> assertThat(namesOf(list.entries())).containsExactly("fast"));
            list.entries();
            assertThat(submissions).hasValue(2);
        } finally {
            release.countDown();
        }

        await().untilAsserted(() -> assertThat(namesOf(list.entries())).containsExactly("slow", "fast"));
    }

    @Test
    void aBoundedReadGivesUpOnAReadStillInFlight() throws SQLException {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean slowHasAnswered = new AtomicBoolean();
        DataSourceMetadataList list = new DataSourceMetadataList(
                Map.of("slow", answeringAfter(release, slowHasAnswered)), clock, ON_A_VIRTUAL_THREAD);
        try {
            assertThat(list.entriesWithin(Duration.ofMillis(10))).isEmpty();
            assertThat(slowHasAnswered).isFalse();
        } finally {
            release.countDown();
        }
    }

    @Test
    void aBoundedReadWaitsForTheReadsInFlight() throws InterruptedException {
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        DataSourceMetadataList list = new DataSourceMetadataList(Map.of("primary", h2("primary")), clock, queued::add);
        AtomicReference<List<DataSourceMetadata>> result = new AtomicReference<>();
        Thread waiter = Thread.ofPlatform().daemon().start(() -> result.set(list.entriesWithin(Duration.ofDays(1))));

        await().until(waiter::getState, Thread.State.TIMED_WAITING::equals);
        queued.forEach(Runnable::run);

        assertThat(waiter.join(Duration.ofSeconds(10))).isTrue();
        assertThat(result.get()).hasSize(1);
    }

    @Test
    void anInterruptEndsTheWaitAndStaysSet() throws SQLException {
        CountDownLatch release = new CountDownLatch(1);
        DataSourceMetadataList list =
                new DataSourceMetadataList(Map.of("slow", answeringAfter(release)), clock, ON_A_VIRTUAL_THREAD);
        Thread.currentThread().interrupt();
        try {
            assertThat(list.entriesWithin(Duration.ofDays(1))).isEmpty();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            release.countDown();
        }
    }

    private static DataSourceMetadata metadata(String name) {
        return new DataSourceMetadata(
                name, "sa", List.of(), "app", DatabaseProduct.H2, Map.of(), "H2", "2", "H2 JDBC Driver");
    }

    private static List<String> namesOf(List<DataSourceMetadata> entries) {
        return entries.stream().map(DataSourceMetadata::dataSourceName).toList();
    }

    private static Map<String, DataSource> inBeanOrder(
            String firstName, DataSource first, String secondName, DataSource second) {
        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put(firstName, first);
        dataSources.put(secondName, second);
        return dataSources;
    }

    private static JdbcDataSource h2(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-list-" + name);
        return dataSource;
    }

    private static DataSource failing(Throwable failure) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(failure);
        return dataSource;
    }

    private static DataSource downOnceThenUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection())
                .thenThrow(new SQLException("db down"))
                .thenAnswer(call -> h2("recovered").getConnection());
        return dataSource;
    }

    private static DataSource answering() throws SQLException {
        return answeringAfter(new CountDownLatch(0));
    }

    /** Answers from an in-memory H2 once {@code release} opens. */
    private static DataSource answeringAfter(CountDownLatch release) throws SQLException {
        return answeringAfter(release, new AtomicBoolean());
    }

    /** Sets {@code hasAnswered} when getConnection() ends, however it ends, so a test can tell a read is still in flight. */
    private static DataSource answeringAfter(CountDownLatch release, AtomicBoolean hasAnswered) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(call -> {
            try {
                Latches.awaitQuietly(release);
                return h2("answering").getConnection();
            } finally {
                hasAnswered.set(true);
            }
        });
        return dataSource;
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
