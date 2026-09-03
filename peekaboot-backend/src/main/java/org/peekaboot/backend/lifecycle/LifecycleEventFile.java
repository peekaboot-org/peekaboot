package org.peekaboot.backend.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.peekaboot.backend.storage.OwnerOnlyFiles;
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

    private final Path file;

    public LifecycleEventFile(Path file) {
        this.file = file;
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
            Optional<LifecycleEvent> event = parse(line);
            if (event.isPresent()) {
                events.add(event.get());
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.debug("Peekaboot lifecycle: skipped {} unreadable line(s) in {}", skipped, file);
        }
        return events;
    }

    /**
     * One line as an event, or empty when it is not one. Jackson fills a record's
     * unmentioned components with defaults, so a foreign or hand-edited line can
     * deserialize without complaint into something that describes nothing that happened;
     * a line with no type is as unusable as one that failed to parse at all.
     */
    private static Optional<LifecycleEvent> parse(String line) {
        try {
            LifecycleEvent event = MAPPER.readValue(line, LifecycleEvent.class);
            return event.type() == null ? Optional.empty() : Optional.of(event);
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    public void write(List<LifecycleEvent> events) throws IOException {
        StringBuilder content = new StringBuilder();
        for (LifecycleEvent event : events) {
            content.append(MAPPER.writeValueAsString(event)).append("\n");
        }
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        OwnerOnlyFiles.replaceAtomically(file, out -> out.write(bytes));
    }
}
