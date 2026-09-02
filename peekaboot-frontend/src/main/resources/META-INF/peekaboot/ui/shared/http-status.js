/**
 * HTTP response status codes: how they read and how they are coloured.
 *
 * The phrases are the IANA HTTP Status Code Registry's, so this table describes the
 * protocol rather than any one server's opinion of it - a code Peekaboot does not know
 * renders as its bare number, never as an invented phrase.
 */
const REASON_PHRASES = {
    100: 'Continue',
    101: 'Switching Protocols',
    102: 'Processing',
    103: 'Early Hints',
    200: 'OK',
    201: 'Created',
    202: 'Accepted',
    203: 'Non-Authoritative Information',
    204: 'No Content',
    205: 'Reset Content',
    206: 'Partial Content',
    207: 'Multi-Status',
    208: 'Already Reported',
    226: 'IM Used',
    300: 'Multiple Choices',
    301: 'Moved Permanently',
    302: 'Found',
    303: 'See Other',
    304: 'Not Modified',
    305: 'Use Proxy',
    307: 'Temporary Redirect',
    308: 'Permanent Redirect',
    400: 'Bad Request',
    401: 'Unauthorized',
    402: 'Payment Required',
    403: 'Forbidden',
    404: 'Not Found',
    405: 'Method Not Allowed',
    406: 'Not Acceptable',
    407: 'Proxy Authentication Required',
    408: 'Request Timeout',
    409: 'Conflict',
    410: 'Gone',
    411: 'Length Required',
    412: 'Precondition Failed',
    413: 'Content Too Large',
    414: 'URI Too Long',
    415: 'Unsupported Media Type',
    416: 'Range Not Satisfiable',
    417: 'Expectation Failed',
    418: "I'm a teapot",
    421: 'Misdirected Request',
    422: 'Unprocessable Content',
    423: 'Locked',
    424: 'Failed Dependency',
    425: 'Too Early',
    426: 'Upgrade Required',
    428: 'Precondition Required',
    429: 'Too Many Requests',
    431: 'Request Header Fields Too Large',
    451: 'Unavailable For Legal Reasons',
    500: 'Internal Server Error',
    501: 'Not Implemented',
    502: 'Bad Gateway',
    503: 'Service Unavailable',
    504: 'Gateway Timeout',
    505: 'HTTP Version Not Supported',
    506: 'Variant Also Negotiates',
    507: 'Insufficient Storage',
    508: 'Loop Detected',
    510: 'Not Extended',
    511: 'Network Authentication Required'
};

/** Placeholder for a trace that carries no HTTP status at all - a scheduled job, say. */
const NO_STATUS = '-';

/**
 * A status as a number, or null when there is none. The API returns it as a number and
 * span tags as a string, so every caller can hand over whichever it happens to hold.
 */
function toCode(status) {
    const code = Number.parseInt(status, 10);
    return Number.isFinite(code) ? code : null;
}

/** A status spelled out, e.g. "404 Not Found"; the bare code when no phrase is registered. */
export function statusLabel(status) {
    const code = toCode(status);
    if (code === null) return NO_STATUS;
    const phrase = REASON_PHRASES[code];
    return phrase ? `${code} ${phrase}` : String(code);
}

/**
 * Badge modifier for a status, one tier per response family. 4xx and 5xx are held apart:
 * a client error is the caller's mistake and gets the softer red, a server error is ours
 * and gets the full one. Informational and unrecognised statuses stay uncoloured rather
 * than borrowing the 5xx tier just for not being a 2xx.
 */
export function statusVariant(status) {
    const code = toCode(status);
    if (code === null) return 'muted';
    switch (Math.floor(code / 100)) {
        case 2: return 'ok';
        case 3: return 'warn';
        case 4: return 'error-soft';
        case 5: return 'error';
        default: return 'muted';
    }
}
