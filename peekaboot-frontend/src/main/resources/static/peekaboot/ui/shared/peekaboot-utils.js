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

    function formatTime(timestamp) {
        if (!timestamp) return '-';
        try {
            const date = new Date(timestamp);
            return date.toLocaleTimeString('en-US', {
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
                fractionalSecondDigits: 3
            });
        } catch (e) {
            return '-';
        }
    }

    return {
        escapeHtml,
        formatDurationMs,
        getDurationClass,
        formatTime
    };
})();

if (typeof module !== 'undefined' && module.exports) {
    module.exports = PeekabootUtils;
}
