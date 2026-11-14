package org.peekaboot.backend.lifecycle;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.peekaboot.backend.storage.StorageDirectory;
import org.peekaboot.backend.storage.WriteOutage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;

/**
 * The application's start/stop history: at most {@link #MAX_EVENTS} events in memory,
 * oldest dropped first, backed by a file when storage is switched on.
 *
 * <p>The file is read on a virtual thread, never on the startup path - an application
 * embedding Peekaboot must not boot one millisecond slower for a dev tool's history.
 * Recording a start therefore queues behind that load, however long it takes, instead of
 * making its caller wait: a slow disk must not cost the run its start. Recording a stop,
 * where there is no later to defer to, waits a bounded time and writes. A stop that
 * cannot see the loaded history by then is dropped: rewriting the file from a log that
 * never read itself would erase what it holds.
 */
public final class LifecycleEventLog {

    private static final Logger log = LoggerFactory.getLogger(LifecycleEventLog.class);

    public static final int MAX_EVENTS = 1000;
    private static final Duration LOAD_WAIT = Duration.ofSeconds(5);
    private static final Executor RECORD_ON_VIRTUAL_THREAD =
            append -> Thread.ofVirtual().name("peekaboot-lifecycle-record").start(append);

    private final LifecycleEventFile file;
    private final Duration loadWait;
    private final Executor recordWhenLoadedExecutor;
    private final List<LifecycleEvent> events = new ArrayList<>();
    private final CountDownLatch loaded = new CountDownLatch(1);
    private final WriteOutage writeOutage = new WriteOutage();

    LifecycleEventLog(LifecycleEventFile file) {
        this(file, LOAD_WAIT);
    }

    /** {@code loadWait} bounds how long a stop or a reader waits for the history; tests shorten it. */
    LifecycleEventLog(LifecycleEventFile file, Duration loadWait) {
        this(file, loadWait, RECORD_ON_VIRTUAL_THREAD);
    }

    /** {@code recordWhenLoadedExecutor} runs {@link #recordWhenLoaded}'s append; tests run it inline. */
    LifecycleEventLog(LifecycleEventFile file, Executor recordWhenLoadedExecutor) {
        this(file, LOAD_WAIT, recordWhenLoadedExecutor);
    }

    LifecycleEventLog(LifecycleEventFile file, Duration loadWait, Executor recordWhenLoadedExecutor) {
        this.file = file;
        this.loadWait = loadWait;
        this.recordWhenLoadedExecutor = recordWhenLoadedExecutor;
    }

    /** The log for {@code storage}: backed by its lifecycle file, or in memory alone while storage is off. */
    public static LifecycleEventLog create(StorageDirectory storage) {
        return new LifecycleEventLog(storage.file(LifecycleEventFile.FILE_NAME)
                .map(LifecycleEventFile::new)
                .orElse(null));
    }

    /** Submits the read; returns immediately. Without a file the log is loaded by definition. */
    public void beginLoad() {
        if (file == null) {
            loaded.countDown();
            return;
        }
        Thread.ofVirtual().name("peekaboot-lifecycle-load").start(() -> {
            try {
                // insertionIndex counts on time order, and a file two instances wrote may not have it.
                List<LifecycleEvent> persisted = file.read().stream()
                        .sorted(Comparator.comparingLong(LifecycleEvent::epochMs))
                        .toList();
                synchronized (events) {
                    events.addAll(0, persisted);
                    trim();
                }
            } finally {
                // However the read ended, the waiters have to be released: a latch never
                // released costs every later API request the full wait, on a thread the
                // host application owns, and the same again at shutdown.
                loaded.countDown();
            }
        });
    }

    /**
     * Appends once the history is in memory, however long that takes, on a virtual thread
     * so the caller never waits.
     */
    public void recordWhenLoaded(LifecycleEvent event) {
        recordWhenLoadedExecutor.execute(() -> appendIfLoaded(awaitLoadWithoutLimit(), event));
    }

    /** Appends and persists before returning; drops the event if the history never arrived. */
    public void recordAndPersist(LifecycleEvent event) {
        appendIfLoaded(awaitLoad(), event);
    }

    /** The history, oldest first. */
    public List<LifecycleEvent> events() {
        awaitLoad();
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private void appendIfLoaded(boolean historyLoaded, LifecycleEvent event) {
        if (!historyLoaded) {
            log.debug("Peekaboot lifecycle: history not loaded, dropping {} event", event.type());
            return;
        }
        append(event);
    }

    /**
     * The write stays inside the monitor. A start is appended from a virtual thread and a
     * stop from the shutdown thread, and in a short-lived run the two overlap: two writers
     * rewriting the same file would trade a lost event for a history whose stop precedes
     * the start it follows, which the next run then reads as an unclean shutdown. Holding
     * the lock across the write costs a reader a few hundred kilobytes of file I/O, twice
     * per run - cheap for the ordering it buys.
     */
    private void append(LifecycleEvent event) {
        synchronized (events) {
            events.add(insertionIndex(event), event);
            trim();
            persist(List.copyOf(events));
        }
    }

    /**
     * Events are filed in time order whatever order they arrive: a stop handed over by a
     * context closing inside the load window would otherwise be persisted ahead of its own
     * start, which the next run reads as an unclean exit with a negative downtime.
     * Callers hold the monitor.
     */
    private int insertionIndex(LifecycleEvent event) {
        int index = events.size();
        while (index > 0 && events.get(index - 1).epochMs() > event.epochMs()) {
            index--;
        }
        return index;
    }

    /** Callers hold the monitor. */
    private void persist(List<LifecycleEvent> snapshot) {
        if (file == null) {
            return;
        }
        try {
            file.write(snapshot);
            writeOutage.succeeded();
        } catch (IOException | JacksonException e) {
            // JacksonException extends RuntimeException, not IOException, so a
            // serialization failure inside file.write() needs its own catch to keep the
            // same promise IOException already gets here: no failure to write this file
            // reaches the application embedding Peekaboot.
            writeOutage.failed(() ->
                    log.warn("Peekaboot lifecycle: cannot write the event log; this run will not be remembered", e));
        }
    }

    /** Callers hold the monitor. */
    private void trim() {
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    private boolean awaitLoad() {
        try {
            return loaded.await(loadWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean awaitLoadWithoutLimit() {
        try {
            loaded.await();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
