/**
 * Client for the Peekaboot API.
 *
 * Overlapping calls to the same path are common -- the 30s auto-refresh, manual
 * refresh and locale changes all race. Each path keeps a generation counter so a
 * slow older request resolves to null, whether it answered or failed, instead of
 * overwriting a newer one or reporting an error over it.
 *
 * That counter is keyed by path by default, which assumes one owner per endpoint.
 * Where two independent views read the same endpoint on their own schedules -- the
 * Insights tab and the Overview tab's stat tiles both load
 * /api/insights/config -- they would supersede each other's calls and each see a
 * null it never asked for, so such a caller passes its own `dedupeKey` and gets a
 * counter of its own. It still de-duplicates against its own repeat calls, which is
 * the whole point of the mechanism.
 */

// Where Peekaboot lives on this server, read off this module's own URL
// (<context-path>/peekaboot/ui/shared/api.js) so a server.servlet.context-path needs no
// configuration on the browser side. The toolbar gets the same value from the server in
// its injected data blob; this default serves the dashboard and anything it opens.
export const BASE_PATH = new URL('../..', import.meta.url).pathname.replace(/\/$/, '');

export function createClient({basePath = BASE_PATH} = {}) {
    const generations = new Map();

    async function get(path, {params, dedupeKey = path} = {}) {
        const generation = (generations.get(dedupeKey) || 0) + 1;
        generations.set(dedupeKey, generation);
        const overtaken = () => generations.get(dedupeKey) !== generation;

        const url = new URL(basePath + path, window.location.origin);
        if (params) {
            Object.entries(params).forEach(([name, value]) => {
                if (value != null) url.searchParams.set(name, String(value));
            });
        }

        try {
            const response = await fetch(url);

            if (overtaken()) return null;
            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const body = await response.json();
            // checked again: a newer request can win while this body is still being parsed
            return overtaken() ? null : body;
        } catch (error) {
            // the caller has moved on to the newer request, so this failure is nobody's to report
            if (overtaken()) return null;
            throw error;
        }
    }

    // basePath is exposed for callers that cannot go through get(), e.g. the
    // insights tab's EventSource
    return {get, basePath};
}
