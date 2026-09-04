package org.peekaboot.testingapp.ui;

import com.microsoft.playwright.Page;
import java.util.Map;

/**
 * Replaces the capture machine's identity on the Overview tab with placeholders, so
 * {@link ScreenshotCapture}'s shots can be published without photographing whoever ran it.
 *
 * <p>The Overview tab renders the OS user with its uid and gid, the pid, the whole parent
 * process chain (on a developer's box the shell and IDE lineage up to init) and every
 * non-local IP address with its reverse-resolved hostname. The images go into two public
 * repositories, so none of that can survive the capture. Scrubbing in the page rather than
 * running the capture somewhere anonymous is deliberate: a container would still publish
 * its own hostname and addresses.
 *
 * <p>The replacements keep the rows the width they really are, so the layout stays honest.
 * Addresses come from the ranges reserved for documentation - {@code 192.0.2.0/24} (RFC
 * 5737) and {@code 2001:db8::/32} (RFC 3849) - which can never collide with a real host.
 *
 * <p>{@code ScreenshotIdentityScrubIT} pins the selectors against the real Overview DOM,
 * because the failure this guards against is silent: a renamed row would leave the capture
 * green and the real value in the published image.
 */
final class ScreenshotIdentityScrub {

    static final String USER = "appuser (uid=1000, gid=1000)";
    static final String PID = "12345";
    static final String PROCESS_TREE = "systemd(1) → sh(842) → java(12345)";
    static final String HOSTNAME = "app-host";
    static final String IPV4_PREFIX = "192.0.2.";
    static final String IPV6_PREFIX = "2001:db8::";

    /**
     * User and PID render whenever the tab has process info at all, so their absence means
     * the card was restructured and this scrub no longer covers it - a hard failure, not a
     * shrug. Process Tree is genuinely optional (a JVM running as pid 1 has no parent), and
     * so is the whole address section (a machine with no non-local address renders none).
     */
    private static final String SCRUB = """
            placeholders => {
                const values = new Map();
                document.querySelectorAll('#os-info .pk-kv').forEach(row => values.set(
                    row.querySelector('.pk-kv__key')?.textContent,
                    row.querySelector('.pk-kv__value')));

                const replace = (key, placeholder, required) => {
                    const cell = values.get(key);
                    if (cell) {
                        cell.textContent = placeholder;
                    } else if (required) {
                        throw new Error('the Overview tab renders no "' + key + '" row; the identity '
                            + 'scrub cannot replace what it cannot find');
                    }
                };
                replace('User', placeholders.user, true);
                replace('PID', placeholders.pid, true);
                replace('Process Tree', placeholders.processTree, false);

                for (const [family, prefix] of [['ipv4', placeholders.ipv4Prefix],
                                                ['ipv6', placeholders.ipv6Prefix]]) {
                    const panel = document.querySelector('#machine-net-' + family);
                    if (!panel) continue;
                    // the panels were built from the real addresses, so each row keeps its
                    // own family - and keeps whether it had a hostname to show
                    panel.querySelectorAll('.pk-machine-net__addr').forEach((row, index) => {
                        const address = prefix + (10 + index);
                        row.textContent = row.textContent.includes(' (')
                            ? address + ' (' + placeholders.hostname + ')'
                            : address;
                    });
                }
            }
            """;

    private ScreenshotIdentityScrub() {}

    /** Call once the Overview tab has rendered, before its screenshot is taken. */
    static void applyTo(Page page) {
        page.evaluate(
                SCRUB,
                Map.of(
                        "user", USER,
                        "pid", PID,
                        "processTree", PROCESS_TREE,
                        "hostname", HOSTNAME,
                        "ipv4Prefix", IPV4_PREFIX,
                        "ipv6Prefix", IPV6_PREFIX));
    }
}
