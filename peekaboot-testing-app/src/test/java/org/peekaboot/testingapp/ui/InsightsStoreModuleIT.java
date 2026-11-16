package org.peekaboot.testingapp.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Exercises dashboard/tabs/insights-store.js, the browser's mirror of the insights rings, and
 * insights-stream.js, which fills it, in a real browser, imported from the running app.
 */
class InsightsStoreModuleIT extends PlaywrightTestBase {

    /**
     * The Insights tab's mirror of the server rings: samples carry no timestamps, so a
     * tick that skips boundaries has to push nulls for the gap or every older sample
     * shifts forward in time; the ring is capped at its size; a series the snapshot did
     * not know starts as an all-null ring; and an event no newer than the ring's end is
     * one the snapshot already contains.
     */
    @Test
    void insightsStoreAppendsTicksWithGapNullsAndIgnoresStaleOnes() {
        String setup = "const s = m.normalizeLevel({level: 0, intervalMs: 1000, endEpochMs: 10000, count: 3,"
                + " series: {a: {values: [1, 2, 3]}}}, 5);";
        assertThat(evalInsightsStore(setup
                        + " m.appendTick(s, {epochMs: 13000, values: {a: 4, b: 7}});"
                        + " return JSON.stringify([s.series.a, s.series.b, s.count, s.endEpochMs]);"))
                .isEqualTo("[[2,3,null,null,4],[null,null,null,null,7],5,13000]");
        assertThat(evalInsightsStore(setup
                        + " m.appendTick(s, {epochMs: 13000, values: {a: 4}});"
                        + " m.appendTick(s, {epochMs: 12000, values: {a: 9}});"
                        + " return JSON.stringify(s.series.a);"))
                .isEqualTo("[2,3,null,null,4]");
    }

    @Test
    void insightsStoreAppendsRollupsPerStat() {
        assertThat(
                        evalInsightsStore(
                                "const r = m.normalizeLevel({level: 1, intervalMs: 60000, endEpochMs: 60000, count: 1,"
                                        + " series: {a: {stats: {min: [1], max: [3], avg: [2]}}}}, 4);"
                                        + " m.appendRollup(r, {level: 1, epochMs: 120000, entries: {a: {min: 0, max: 5, avg: 2.5}}});"
                                        + " return JSON.stringify([r.series.a.min, r.series.a.max, r.series.a.avg, r.series.a.p99, r.count]);"))
                .isEqualTo("[[1,0],[3,5],[2,2.5],[null],2]");
    }

    @Test
    void insightsStoreCountsMissedSamplesCappedAtTheRingSize() {
        String snapshot = "{endEpochMs: 10000, intervalMs: 1000, size: 5}";
        assertThat(importModule(
                        "dashboard/tabs/insights-store.js", "m.missedSamples(" + snapshot + ", {epochMs: 11000})"))
                .isEqualTo(0);
        assertThat(importModule(
                        "dashboard/tabs/insights-store.js", "m.missedSamples(" + snapshot + ", {epochMs: 13000})"))
                .isEqualTo(2);
        assertThat(importModule(
                        "dashboard/tabs/insights-store.js", "m.missedSamples(" + snapshot + ", {epochMs: 20000})"))
                .isEqualTo(5);
    }

    /**
     * A gap longer than the ring itself leaves nothing of the old samples: the mirror is
     * the newest sample behind a ring's worth of nulls, and the count is the ring size.
     */
    @Test
    void insightsStoreCapsAGapLongerThanTheRingAtTheRingSize() {
        assertThat(evalInsightsStore(
                        "const s = m.normalizeLevel({level: 0, intervalMs: 1000, endEpochMs: 10000, count: 3,"
                                + " series: {a: {values: [1, 2, 3]}}}, 5);"
                                + " m.appendTick(s, {epochMs: 99000, values: {a: 4}});"
                                + " return JSON.stringify([s.series.a, s.count]);"))
                .isEqualTo("[[null,null,null,null,4],5]");
    }

    /**
     * The stream opens before the first snapshot is fetched, and the server may serve that
     * snapshot before a sample it then streams: an empty ring cannot place a later sample
     * behind a gap, so a rollup arriving while the request is in flight must still land.
     */
    @Test
    void insightsStreamKeepsARollupThatArrivesWhileAnEmptySnapshotLoads() {
        assertThat(evalInsightsStream(" const load = stream.ensureLevel(1);"
                        + " emit('rollup', {level: 1, epochMs: 120000, entries: {a: {avg: 2}}});"
                        + " respond({level: 1, intervalMs: 60000, endEpochMs: 0, count: 0, series: {}});"
                        + " await load;"
                        + " emit('rollup', {level: 1, epochMs: 180000, entries: {a: {avg: 3}}});"
                        + " const s = stream.level(1);"
                        + " return JSON.stringify([s.series.a.avg, s.count, s.endEpochMs]);"))
                .isEqualTo("[[2,3],2,180000]");
    }

    /** Of the ticks that arrive while a snapshot loads, the ones it already holds are not appended twice. */
    @Test
    void insightsStreamAppliesOnlyTheTicksALoadingSnapshotDoesNotHold() {
        assertThat(evalInsightsStream(" const load = stream.ensureLevel(0);"
                        + " emit('tick', {epochMs: 11000, values: {a: 2}});"
                        + " emit('tick', {epochMs: 12000, values: {a: 3}});"
                        + " respond({level: 0, intervalMs: 1000, endEpochMs: 11000, count: 2,"
                        + "   series: {a: {values: [1, 2]}}});"
                        + " await load;"
                        + " const s = stream.level(0);"
                        + " return JSON.stringify([s.series.a, s.count, s.endEpochMs]);"))
                .isEqualTo("[[1,2,3],3,12000]");
    }

    /**
     * A reconnect re-snapshots every loaded level; a rollup arriving during that request
     * must survive the fresh snapshot replacing the one it was applied to.
     */
    @Test
    void insightsStreamKeepsARollupThatArrivesWhileAResyncSnapshotLoads() {
        assertThat(evalInsightsStream(" const load = stream.ensureLevel(1);"
                        + " respond({level: 1, intervalMs: 60000, endEpochMs: 120000, count: 1,"
                        + "   series: {a: {stats: {avg: [2]}}}});"
                        + " await load;"
                        + " emit('error', {});"
                        + " emit('open', {});"
                        + " await settle();"
                        + " emit('rollup', {level: 1, epochMs: 240000, entries: {a: {avg: 4}}});"
                        + " respond({level: 1, intervalMs: 60000, endEpochMs: 180000, count: 2,"
                        + "   series: {a: {stats: {avg: [2, 3]}}}});"
                        + " await settle();"
                        + " const s = stream.level(1);"
                        + " return JSON.stringify([s.series.a.avg, s.count, s.endEpochMs]);"))
                .isEqualTo("[[2,3,4],3,240000]");
    }

    /** Runs {@code body}, a function body over the insights store module {@code m}, and returns what it returns. */
    private Object evalInsightsStore(String body) {
        return importModule("dashboard/tabs/insights-store.js", "(() => {" + body + "})()");
    }

    /**
     * Runs {@code body}, an async function body over a connected insights stream {@code stream},
     * with {@code emit(type, data)} firing a server event, {@code respond(body)} answering the
     * oldest open /api/insights/data request, and {@code settle()} letting pending work run.
     */
    private Object evalInsightsStream(String body) {
        return importModule(
                "dashboard/tabs/insights-stream.js",
                "(async () => {"
                        + " let source;"
                        + " window.EventSource = class {"
                        + "   static CLOSED = 2;"
                        + "   constructor() { source = this; this.readyState = 1; this.listeners = {}; }"
                        + "   addEventListener(type, listener) { (this.listeners[type] ??= []).push(listener); }"
                        + "   close() {}"
                        + " };"
                        + " const emit = (type, data) => source.listeners[type]"
                        + "   .forEach(listener => listener({data: JSON.stringify(data), target: source}));"
                        + " const dataRequests = [];"
                        + " const respond = body => dataRequests.shift()(body);"
                        + " const settle = () => new Promise(resolve => setTimeout(resolve));"
                        + " const client = {basePath: '', get: path => path === '/api/insights/data'"
                        + "   ? new Promise(resolve => dataRequests.push(resolve)) : Promise.resolve({events: []})};"
                        + " const stream = m.createInsightsStream({client, sizeOf: () => 5,"
                        + "   onDirty() {}, onResynced() {}, onClosed() {}, onOpen() {}});"
                        + " stream.connect();"
                        + body
                        + "})()");
    }
}
