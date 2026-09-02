package org.eclipse.jetty.io;

import java.io.IOException;

/**
 * Stand-in at Jetty's exact coordinates: {@code DevToolbarFilter} recognises Jetty's
 * client-abort exception by simple name (no Jetty dependency), and this is what lets a
 * test pin the real class matching. Like the real one, it extends {@link IOException}
 * and typically carries no message.
 */
public class EofException extends IOException {}
