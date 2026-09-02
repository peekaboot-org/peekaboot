/** Icons are literal characters so callers can assign them with textContent. */
const ROOT_ACTIONS = {
    HTTP_REQUEST:     {icon: '\u{1F310}', label: 'HTTP Request'},
    SCHEDULED_JOB:    {icon: '\u{1F551}', label: 'Scheduled Job'},
    MESSAGE_CONSUMER: {icon: '\u{1F4E9}', label: 'Message Consumer'},
    RPC_CALL:         {icon: '\u{1F517}', label: 'RPC Call'},
    DATABASE:         {icon: '\u{1F5C2}', label: 'Database'},
    CONNECTION_POOL:  {icon: '\u{1F50C}', label: 'Connection Pool'},
    INTERNAL:         {icon: '⚙',    label: 'Internal'},
    UNKNOWN:          {icon: '❓',    label: 'Unknown'}
};

export const ROOT_ACTION_TYPES = Object.keys(ROOT_ACTIONS);

export function rootActionIcon(type) {
    return (ROOT_ACTIONS[type] || ROOT_ACTIONS.UNKNOWN).icon;
}

export function rootActionLabel(type) {
    return (ROOT_ACTIONS[type] || ROOT_ACTIONS.UNKNOWN).label;
}
