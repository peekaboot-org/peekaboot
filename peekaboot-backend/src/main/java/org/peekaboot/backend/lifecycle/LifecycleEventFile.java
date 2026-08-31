package org.peekaboot.backend.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The lifecycle log on disk: one JSON object per line, rewritten whole and moved into
 * place atomically.
 *
 * <p>Rewriting the whole file rather than appending is what keeps this small: two
 * writes of a few hundred kilobytes per run, no partially written line to parse
 * around, and no second code path to trim the file back to its cap. A line that is
 * damaged anyway costs only itself.
 */
public final class LifecycleEventFile {

    private static final Logger log = LoggerFactory.getLogger(LifecycleEventFile.class);

    /** Private, not injected: the host application's Jackson configuration must never reach this file's format. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static final String FILE_NAME = "lifecycle.jsonl";
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path file;
    private final Path temp;

    public LifecycleEventFile(Path file) {
        this.file = file;
        this.temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
    }

    /** The log as written, oldest first; an absent or unreadable file is simply no history. */
    public List<LifecycleEvent> read() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.info("Peekaboot lifecycle: cannot read {}; starting with an empty log", file, e);
            return List.of();
        }
        List<LifecycleEvent> events = new ArrayList<>(lines.size());
        int skipped = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                events.add(MAPPER.readValue(line, LifecycleEvent.class));
            } catch (JacksonException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.debug("Peekaboot lifecycle: skipped {} unreadable line(s) in {}", skipped, file);
        }
        return events;
    }

    public void write(List<LifecycleEvent> events) throws IOException {
        StringBuilder content = new StringBuilder();
        for (LifecycleEvent event : events) {
            content.append(MAPPER.writeValueAsString(event)).append("\n");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(temp, content.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
