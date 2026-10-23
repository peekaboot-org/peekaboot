package org.peekaboot.backend.domain.trace;

import java.util.List;

/**
 * A query's statement as the trace view shows it, every part masked: the SQL as captured, the
 * same SQL formatted (null when no formatter is available or it could not lay the SQL out),
 * the bind parameters as SQL literals, one list per parameter set - several for a batch,
 * none when the statement had no parameters - and how many parameter sets a capped batch left
 * out of that list.
 */
public record SqlStatement(String text, String formatted, List<List<String>> parameters, int omittedParameterSets) {}
