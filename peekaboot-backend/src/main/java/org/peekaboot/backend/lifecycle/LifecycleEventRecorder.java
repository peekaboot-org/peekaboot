package org.peekaboot.backend.lifecycle;

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

    private final LifecycleEventLog log;
    private final BuildInfoProvider buildInfoProvider;
    private final GitProperties gitProperties;
    private final ApplicationContext ownContext;

    public LifecycleEventRecorder(
            LifecycleEventLog log,
            BuildInfoProvider buildInfoProvider,
            GitProperties gitProperties,
            ApplicationContext ownContext) {
        this.log = log;
        this.buildInfoProvider = buildInfoProvider;
        this.gitProperties = gitProperties;
        this.ownContext = ownContext;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        log.recordWhenLoaded(LifecycleEvent.start(
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
        log.recordAndPersist(LifecycleEvent.stop(
                System.currentTimeMillis(), ProcessHandle.current().pid()));
    }
}
