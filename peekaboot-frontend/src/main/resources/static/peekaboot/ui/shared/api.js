/**
 * Client for the Peekaboot API.
 *
 * Overlapping calls to the same path are common -- the 30s auto-refresh, manual
 * refresh and locale changes all race. Each path keeps a generation counter so a
 * slow older response resolves to null instead of overwriting a newer one.
 *
 * That counter is keyed by path by default, which assumes one owner per endpoint.
 * Where two independent views read the same endpoint on their own schedules -- the
 * Insights tab and the Overview tab's stat tiles both load
 * /api/insights/config -- they would supersede each other's calls and each see a
 * null it never asked for, so such a caller passes its own `dedupeKey` and gets a
 * counter of its own. It still de-duplicates against its own repeat calls, which is
 * the whole point of the mechanism.
 */
export function createClient({basePath = '/peekaboot'} = {}) {
    const generations = new Map();

    async function get(path, {params, dedupeKey = path} = {}) {
        const generation = (generations.get(dedupeKey) || 0) + 1;
        generations.set(dedupeKey, generation);

        const url = new URL(basePath + path, window.location.origin);
        if (params) {
            Object.entries(params).forEach(([name, value]) => {
                if (value != null) url.searchParams.set(name, String(value));
            });
        }

        const response = await fetch(url);

        if (generations.get(dedupeKey) !== generation) return null;
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const body = await response.json();
        return generations.get(dedupeKey) === generation ? body : null;
    }

    // basePath is exposed for callers that cannot go through get(), e.g. the
    // insights tab's EventSource
    return {get, basePath};
}
