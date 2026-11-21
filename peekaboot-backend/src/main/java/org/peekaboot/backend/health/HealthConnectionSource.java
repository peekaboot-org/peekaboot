package org.peekaboot.backend.health;

import java.util.Map;
import org.peekaboot.backend.domain.health.HealthConnection;

/** Describes what the children of one health component connect to. */
public interface HealthConnectionSource {

    /** The top-level health component this source describes, such as {@code db}. */
    String component();

    /** Keyed by bean name. Read from configuration, never from a connection. */
    Map<String, HealthConnection> connections();
}
