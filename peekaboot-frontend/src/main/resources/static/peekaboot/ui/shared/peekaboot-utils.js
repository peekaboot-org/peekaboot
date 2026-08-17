/**
 * Shared utility functions for Peekaboot UI components.
 */
const PeekabootUtils = (function() {
    'use strict';

    const HTML_ESCAPES = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};

    function escapeHtml(text) {
        if (text == null) return '';
        return String(text).replace(/[&<>"']/g, c => HTML_ESCAPES[c]);
    }

    function formatDurationMs(ms) {
        if (ms == null) return '-';
        if (ms < 1) return '<1ms';
        if (ms < 1000) return Math.round(ms) + 'ms';
        if (ms < 60000) return (ms / 1000).toFixed(2) + 's';
        return (ms / 60000).toFixed(2) + 'm';
    }

    function getDurationClass(ms) {
        if (ms > 500) return 'very-slow';
        if (ms > 100) return 'slow';
        return '';
    }

    /** Formats API host objects ({hostname, port, instanceName}) as "hostname:port, ...". */
    function formatHosts(hosts) {
        if (!hosts || hosts.length === 0) return 'unknown';
        return hosts.map(h => h.hostname + (h.port ? ':' + h.port : '')).join(', ');
    }

    return {
        escapeHtml,
        formatDurationMs,
        getDurationClass,
        formatHosts
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = PeekabootUtils;
}
