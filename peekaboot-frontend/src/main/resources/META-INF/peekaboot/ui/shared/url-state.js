/**
 * Hash-based routing state, shared by the dashboard shell (main.js) and the trace-detail
 * overlay.
 *
 * Format: `#<tab>[/<detail>[/<subview>]][?<query>]`, e.g.
 *   `#traces/abc123/logs?span=c257a660&level=WARN&q=timeout`
 *   `#traces?bucket=errors&type=HTTP_REQUEST,SCHEDULED_JOB`
 *   `#loggers?q=peekaboot&configured=1`
 *
 * Push/replace rule: structural segments (tab, detail) push a new history entry via
 * pushAppHash - Back should walk dashboard tabs and close the overlay one step at a time.
 * subview and params are written via replaceAppHash instead - switching the overlay's
 * internal tab, or typing into a filter, must never add a Back stop of its own. Every hash
 * write in the app is expected to go through pushAppHash/replaceAppHash; nothing should
 * assign location.hash directly.
 */

/** Parses a `#...` hash (or any equivalent string) into routing state. */
export function parseAppHash(hash = window.location.hash) {
    const raw = hash.startsWith('#') ? hash.slice(1) : hash;
    const [path, query = ''] = raw.split('?');
    if (!path) return {tab: 'overview', detail: null, subview: null, params: {}};

    const [tab, detail = null, subview = null] = path.split('/');
    const params = {};
    for (const [key, value] of new URLSearchParams(query)) {
        params[key] = value;
    }
    return {tab: tab || 'overview', detail, subview, params};
}

/**
 * Builds a `#...` hash string from routing state. Omits empty segments (a subview is only
 * ever emitted alongside a detail, matching the format above) and empty-string params.
 */
export function buildAppHash({tab, detail = null, subview = null, params = {}}) {
    const segments = [tab];
    if (detail) {
        segments.push(detail);
        if (subview) segments.push(subview);
    }

    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value !== '' && value != null) query.set(key, value);
    }

    const queryString = query.toString();
    return `#${segments.join('/')}${queryString ? `?${queryString}` : ''}`;
}

/** Pushes a new history entry for a structural (tab and/or detail) change. */
export function pushAppHash(state) {
    const hash = buildAppHash(state);
    if (hash !== window.location.hash) {
        history.pushState(null, '', hash);
    }
}

/** Replaces the current history entry - for subview/params changes that must not push. */
export function replaceAppHash(state) {
    const hash = buildAppHash(state);
    if (hash !== window.location.hash) {
        history.replaceState(null, '', hash);
    }
}
