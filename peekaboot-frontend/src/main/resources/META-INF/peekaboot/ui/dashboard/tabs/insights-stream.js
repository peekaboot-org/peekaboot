/**
 * The Insights tab's live data: a client-side mirror of the server's ring buffers (one
 * snapshot per loaded level, see insights-store.js), the EventSource that keeps it
 * current with tick/rollup events instead of polling, and the resync that repairs it
 * after a reconnect. No DOM: what a delta means for the charts is the caller's business,
 * reported through the callbacks.
 *
 *   sizeOf(level)    -> the configured ring size of a level (normalizeLevel's `size`)
 *   onDirty(level)   -> a level's mirror changed (a delta, or a resync's fresh snapshot)
 *   onResynced()     -> a resync finished: the restart markers may have changed too
 *   onClosed()       -> the browser gave up on the stream (readyState CLOSED)
 *   onOpen()         -> the stream is (back) open
 */
import {normalizeLevel, appendTick, appendRollup} from './insights-store.js';

export function createInsightsStream({client, sizeOf, onDirty, onResynced, onClosed, onOpen}) {
    const levels = new Map();       // level index -> snapshot (see normalizeLevel)
    const levelLoads = new Map();   // level index -> in-flight load promise
    let lifecycleEvents = [];
    let source = null;
    let resyncPending = false;

    /** One dedupe key per level, so a level-1 load cannot cancel an in-flight level-0 load (see shared/api.js). */
    async function loadLevel(level) {
        const body = await client.get('/api/insights/data', {params: {level}, dedupeKey: `insights-data-${level}`});
        if (body) levels.set(level, normalizeLevel(body, sizeOf(level)));
    }

    /** Loads a level at most once; concurrent callers share the in-flight request. */
    function ensureLevel(level) {
        if (levels.has(level)) return Promise.resolve();
        if (!levelLoads.has(level)) {
            levelLoads.set(level, loadLevel(level).finally(() => levelLoads.delete(level)));
        }
        return levelLoads.get(level);
    }

    /**
     * The application's own start/stop history. Absent (lifecycle disabled, or an older
     * backend) simply means no markers - never a failed tab.
     */
    async function loadLifecycleEvents() {
        try {
            const body = await client.get('/api/lifecycle/events');
            lifecycleEvents = body?.events ?? [];
        } catch (error) {
            console.warn('Insights: restart markers unavailable:', error);
            lifecycleEvents = [];
        }
    }

    function connect() {
        source = new EventSource(client.basePath + '/api/insights/stream');

        // a tick carries this level's series values and nothing else - the stat tiles come
        // from /api/insights/config, on the Overview tab's own 30s cycle
        source.addEventListener('tick', event => {
            appendTick(levels.get(0), JSON.parse(event.data));
            onDirty(0);
        });

        source.addEventListener('rollup', event => {
            const rollup = JSON.parse(event.data);
            appendRollup(levels.get(rollup.level), rollup);
            onDirty(rollup.level);
        });

        // EventSource reconnects on its own, but the deltas missed while it was down
        // leave the mirrored rings out of step - every loaded level is re-snapshotted
        // before the next delta is applied. A browser that has given up instead (CLOSED:
        // the endpoint answering 404 behind a proxy, an expired session) never fires open
        // again, and a frozen chart is indistinguishable from a quiet application, so the
        // caller is told until reconnectIfClosed() manages to reconnect.
        source.addEventListener('error', event => {
            resyncPending = true;
            if (event.target.readyState === EventSource.CLOSED) onClosed();
        });
        source.addEventListener('open', () => {
            onOpen();
            if (!resyncPending) return;
            resyncPending = false;
            // the application may still be coming up - a failed snapshot is retried on the
            // next reconnect rather than leaving the mirrored rings silently out of step
            resync().catch(error => {
                console.warn('Insights: resync after reconnect failed, retrying on the next reconnect:', error);
                resyncPending = true;
            });
        });
    }

    /** Replaces a stream the browser closed for good; the open that follows resyncs (resyncPending was set by the closing error). */
    function reconnectIfClosed() {
        if (source?.readyState !== EventSource.CLOSED) return;
        source.close();
        connect();
    }

    /** Re-snapshots every loaded level after a reconnect. */
    async function resync() {
        // an application that restarted under an open dashboard has a new event to
        // draw, fetched before the level loop so every chart is updated in one pass
        await loadLifecycleEvents();
        for (const level of [...levels.keys()]) {
            // a load that is still in flight already returns post-reconnect data, and
            // a second request for the same path would only cancel it out
            if (levelLoads.has(level)) continue;
            await loadLevel(level);
            onDirty(level);
        }
        onResynced();
    }

    function close() {
        source?.close();
        source = null;
    }

    return {
        ensureLevel,
        level: index => levels.get(index),
        loadLifecycleEvents,
        events: () => lifecycleEvents,
        connect,
        reconnectIfClosed,
        close
    };
}
