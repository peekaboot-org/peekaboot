package org.peekaboot.backend.lifecycle;

import java.time.Clock;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Writes the application's own start and stop into the lifecycle log.
 *
 * <p>The start is stamped with the ready event's own timestamp - the moment the
 * application could actually serve, which is what a marker on a chart should point at.
 */
public class LifecycleEventRecorder {

    private final LifecycleEventLog eventLog;
    private final BuildInfoProvider buildInfoProvider;
    private final @Nullable GitProperties hostLocatedGitProperties;
    private final ApplicationContext ownContext;
    private final Clock clock;

    /**
     * Records the start with the application's own git facts.
     *
     * @param hostLocatedGitProperties Boot's, where the host names its git.properties with {@code spring.info.git.location}; null has the recorder read the application's own
     */
    public LifecycleEventRecorder(
            LifecycleEventLog eventLog,
            BuildInfoProvider buildInfoProvider,
            @Nullable GitProperties hostLocatedGitProperties,
            ApplicationContext ownContext) {
        this(eventLog, buildInfoProvider, hostLocatedGitProperties, ownContext, Clock.systemUTC());
    }

    /** {@code clock} supplies the instant a stop is stamped with; tests fix it. */
    LifecycleEventRecorder(
            LifecycleEventLog eventLog,
            BuildInfoProvider buildInfoProvider,
            @Nullable GitProperties hostLocatedGitProperties,
            ApplicationContext ownContext,
            Clock clock) {
        this.eventLog = eventLog;
        this.buildInfoProvider = buildInfoProvider;
        this.hostLocatedGitProperties = hostLocatedGitProperties;
        this.ownContext = ownContext;
        this.clock = clock;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        if (!ContextEvents.fromOwnContext(event, ownContext)) {
            return;
        }
        GitProperties gitProperties = hostLocatedGitProperties != null
                ? hostLocatedGitProperties
                : ApplicationGitProperties.of(event.getSpringApplication());
        eventLog.recordWhenLoaded(LifecycleEvent.start(
                event.getTimestamp(),
                ProcessHandle.current().pid(),
                buildInfoProvider.getEntries(),
                InfoEntries.of(gitProperties)));
    }

    @EventListener
    public void onClosed(ContextClosedEvent event) {
        if (!ContextEvents.fromOwnContext(event, ownContext)) {
            return;
        }
        eventLog.recordAndPersist(
                LifecycleEvent.stop(clock.millis(), ProcessHandle.current().pid()));
    }
}
