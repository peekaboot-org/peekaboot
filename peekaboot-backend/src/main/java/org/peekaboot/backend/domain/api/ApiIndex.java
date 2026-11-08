package org.peekaboot.backend.domain.api;

import java.util.List;

/**
 * The index at {@code GET /peekaboot/api}, which names where the OpenAPI document is and every
 * endpoint it describes.
 *
 * @param openapi the document's path, as a client addresses it
 * @param endpoints every path the document describes, in its order
 */
public record ApiIndex(String openapi, List<Endpoint> endpoints) {

    /**
     * One endpoint and what it is for.
     *
     * @param path the path template as a client addresses it, such as {@code /peekaboot/api/traces/{traceId}}
     * @param purpose the operation's summary in the document, one line
     */
    public record Endpoint(String path, String purpose) {}
}
