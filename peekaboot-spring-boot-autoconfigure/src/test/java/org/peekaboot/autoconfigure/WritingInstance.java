package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionTimeoutException;

/** Another JVM that holds a storage directory's lock, so a context started on that directory is the second instance. */
final class WritingInstance implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(30);

    /** The backend's lock holder, started from its single source file as the backend's own lock test starts it. */
    private static final Path PROGRAM = BuildFiles.reactorRoot()
            .resolve(Path.of("peekaboot-backend", "src", "test", "resources", "storage", "HoldLock.java"));

    private final Process holder;

    private WritingInstance(Process holder) {
        this.holder = holder;
    }

    /** Returns once the other JVM holds the lock of {@code storageDir}. Its output goes to a file in {@code scratch}. */
    static WritingInstance on(Path storageDir, Path scratch) throws IOException, InterruptedException {
        Path output = scratch.resolve("holder.out");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path lockFile = storageDir.resolve(".lock");
        WritingInstance instance =
                new WritingInstance(new ProcessBuilder(java.toString(), PROGRAM.toString(), lockFile.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start());
        try {
            await().atMost(WAIT).until(() -> hasLocked(output) || !instance.holder.isAlive());
            assertThat(hasLocked(output)).as(Files.readString(output)).isTrue();
        } catch (ConditionTimeoutException | AssertionError e) {
            instance.close();
            throw e;
        }
        return instance;
    }

    private static boolean hasLocked(Path output) throws IOException {
        return Files.readAllLines(output).contains("locked");
    }

    /** The holder ends when its standard input does. */
    @Override
    public void close() throws IOException, InterruptedException {
        holder.getOutputStream().close();
        if (!holder.waitFor(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            holder.destroyForcibly();
        }
    }
}
