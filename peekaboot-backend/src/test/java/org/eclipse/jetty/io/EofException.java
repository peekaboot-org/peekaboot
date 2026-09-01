package org.eclipse.jetty.io;

import java.io.IOException;

/**
 * Stand-in with Jetty's exact class name: {@code DevToolbarFilter} recognises Jetty's
 * client-abort exception by name (no Jetty dependency), and this is what lets a test
 * exercise that match. Like the real one, it extends {@link IOException} and typically
 * carries no message.
 */
public class EofException extends IOException {}
