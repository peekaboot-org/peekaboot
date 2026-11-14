package org.peekaboot.backend.datasource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The metadata of every {@code DataSource} bean, as one bean of its own type.
 *
 * <p>A {@code List<DataSourceMetadata>} bean would not do: Spring resolves that type by
 * collecting the {@code DataSourceMetadata} beans first and only falls back to a list bean
 * when there are none, so a single application bean of that type would silently replace
 * the whole list with itself.
 *
 * <p>A DataSource is read on first use, on a virtual thread, and never while the context
 * refreshes: a database that is down or slow would hold the start for the pool's timeout.
 * A read that fails is repeated by the next reader once 30 seconds have passed.
 */
public final class DataSourceMetadataList {

    static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(DataSourceMetadataList.class);

    private static final Executor READ_ON_VIRTUAL_THREAD =
            read -> Thread.ofVirtual().name("peekaboot-datasource-metadata").start(read);

    public static final DataSourceMetadataList EMPTY = new DataSourceMetadataList(List.of());

    private final List<Slot> slots;

    private final Clock clock;

    private final Executor reader;

    /** Metadata stated outright. Nothing is ever read. */
    public DataSourceMetadataList(List<DataSourceMetadata> entries) {
        this(entries.stream().map(Slot::stating).toList(), Clock.systemUTC(), READ_ON_VIRTUAL_THREAD);
    }

    /** The seam: tests state the clock and run reads inline. */
    DataSourceMetadataList(Map<String, DataSource> dataSources, Clock clock, Executor reader) {
        this(
                dataSources.entrySet().stream()
                        .map(dataSource -> Slot.reading(dataSource.getKey(), dataSource.getValue()))
                        .toList(),
                clock,
                reader);
    }

    private DataSourceMetadataList(List<Slot> slots, Clock clock, Executor reader) {
        this.slots = slots;
        this.clock = clock;
        this.reader = reader;
    }

    /** Reads each DataSource on first use, on a virtual thread, with the system clock. */
    public static DataSourceMetadataList resolving(Map<String, DataSource> dataSources) {
        return new DataSourceMetadataList(dataSources, Clock.systemUTC(), READ_ON_VIRTUAL_THREAD);
    }

    /** Every DataSource, in bean order. Never connects. */
    public List<String> dataSourceNames() {
        return slots.stream().map(slot -> slot.name).toList();
    }

    /** What has been read so far, in bean order, and starts a read for each DataSource that is due. Never waits. */
    public List<DataSourceMetadata> entries() {
        startDueReads();
        return readSoFar();
    }

    /** {@link #entries()}, after waiting until every read in flight has ended or {@code wait} has passed. */
    public List<DataSourceMetadata> entriesWithin(Duration wait) {
        startDueReads();
        CompletableFuture<?>[] inFlight =
                slots.stream().map(Slot::inFlight).filter(Objects::nonNull).toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(inFlight).get(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException expected) {
            // a read that failed or is still in flight has no entry, which is all a caller is told
        }
        return readSoFar();
    }

    private void startDueReads() {
        slots.forEach(slot -> slot.startReadIfDue(clock, reader));
    }

    private List<DataSourceMetadata> readSoFar() {
        return slots.stream().map(Slot::metadata).filter(Objects::nonNull).toList();
    }

    /** One DataSource: its metadata once read, until then the read in flight and when the last one failed. */
    private static final class Slot {

        private final String name;

        /** Null where the metadata was stated. */
        private final @Nullable DataSource dataSource;

        private @Nullable DataSourceMetadata metadata;

        /** Done once the outcome of its read is recorded, so it is also what a waiter waits on. */
        private @Nullable CompletableFuture<DataSourceMetadata> inFlight;

        private @Nullable Instant failedAt;

        private Slot(String name, @Nullable DataSource dataSource, @Nullable DataSourceMetadata metadata) {
            this.name = name;
            this.dataSource = dataSource;
            this.metadata = metadata;
        }

        static Slot stating(DataSourceMetadata metadata) {
            return new Slot(metadata.dataSourceName(), null, metadata);
        }

        static Slot reading(String name, DataSource dataSource) {
            return new Slot(name, dataSource, null);
        }

        synchronized @Nullable DataSourceMetadata metadata() {
            return metadata;
        }

        synchronized @Nullable CompletableFuture<DataSourceMetadata> inFlight() {
            return inFlight;
        }

        void startReadIfDue(Clock clock, Executor reader) {
            DataSource source = dataSource;
            CompletableFuture<Void> start;
            synchronized (this) {
                if (source == null || !isDue(clock.instant())) {
                    return;
                }
                start = new CompletableFuture<>();
                inFlight = start.thenApplyAsync(started -> read(source), reader)
                        .whenComplete((read, failure) -> record(read, failure, clock.instant()));
            }
            // outside the lock: a reader that runs inline would otherwise connect while holding it
            start.complete(null);
        }

        private boolean isDue(Instant now) {
            boolean reading = inFlight != null && !inFlight.isDone();
            boolean resting = failedAt != null && now.isBefore(failedAt.plus(RETRY_AFTER));
            return metadata == null && !reading && !resting;
        }

        private DataSourceMetadata read(DataSource source) {
            try {
                return DataSourceMetadata.read(name, source);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }

        private void record(@Nullable DataSourceMetadata read, @Nullable Throwable failure, Instant now) {
            if (failure == null) {
                synchronized (this) {
                    metadata = read;
                }
                return;
            }
            boolean failedBefore;
            synchronized (this) {
                failedBefore = failedAt != null;
                failedAt = now;
            }
            // a failed stage hands its exception over wrapped in a CompletionException
            String reason = failure.getCause().getMessage();
            if (failedBefore) {
                log.debug("Failed to extract metadata from DataSource '{}' again: {}", name, reason);
            } else {
                log.warn("Failed to extract metadata from DataSource '{}': {}", name, reason);
            }
        }
    }
}
