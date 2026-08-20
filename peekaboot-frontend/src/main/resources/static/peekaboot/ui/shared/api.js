/**
 * Client for the Peekaboot API.
 *
 * Overlapping calls to the same path are common -- the 30s auto-refresh, manual
 * refresh and locale changes all race. Each path keeps a generation counter so a
 * slow older response resolves to null instead of overwriting a newer one.
 */
export function createClient({basePath = '/peekaboot'} = {}) {
    const generations = new Map();

    async function get(path, {params} = {}) {
        const generation = (generations.get(path) || 0) + 1;
        generations.set(path, generation);

        const url = new URL(basePath + path, window.location.origin);
        if (params) {
            Object.entries(params).forEach(([name, value]) => {
                if (value != null) url.searchParams.set(name, String(value));
            });
        }

        const response = await fetch(url);

        if (generations.get(path) !== generation) return null;
        if (!response.ok) throw new Error(`HTTP ${response.status}`);

        const body = await response.json();
        return generations.get(path) === generation ? body : null;
    }

    return {get};
}
