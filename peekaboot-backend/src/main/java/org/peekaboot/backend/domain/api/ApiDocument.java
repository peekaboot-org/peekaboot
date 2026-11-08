package org.peekaboot.backend.domain.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.peekaboot.backend.config.PeekabootJson;
import org.peekaboot.backend.config.PeekabootPaths;
import tools.jackson.databind.node.ObjectNode;

/**
 * The hand-written OpenAPI document that describes Peekaboot's API, read once from the backend
 * jar. It names every path as Spring maps it, below the DispatcherServlet, so each copy it
 * serves names the requesting client's context path and DispatcherServlet prefix as its server.
 */
public final class ApiDocument {

    /** The root every API path starts with. */
    public static final String API_ROOT = PeekabootPaths.BASE_PATH + "/api";

    /** Where the document is served. */
    public static final String DOCUMENT_PATH = API_ROOT + "/openapi.json";

    /** Where the backend jar carries the document. */
    static final String RESOURCE = PeekabootPaths.CLASSPATH_ROOT + "/api/openapi.json";

    private final ObjectNode document;

    private final List<ApiIndex.Endpoint> endpoints;

    private ApiDocument(ObjectNode document) {
        this.document = document;
        this.endpoints = document.path("paths")
                .propertyStream()
                .map(path -> new ApiIndex.Endpoint(
                        path.getKey(),
                        path.getValue().path("get").path("summary").asString()))
                .toList();
    }

    /** The document the backend jar ships. */
    public static ApiDocument bundled() {
        try (InputStream in = ApiDocument.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The backend jar carries no " + RESOURCE);
            }
            return new ApiDocument((ObjectNode) PeekabootJson.MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A copy whose one server is {@code root}, the context path and DispatcherServlet prefix a
     * client addresses, or {@code /} where both are empty. Each call copies, so no request sees
     * another's server.
     */
    public OpenApiDocument forServer(String root) {
        ObjectNode copy = document.deepCopy();
        copy.putArray("servers").addObject().put("url", root.isEmpty() ? "/" : root);
        return new OpenApiDocument(copy);
    }

    /** Every path the document describes with its summary, each behind {@code root} as {@link #forServer} names it. */
    public ApiIndex index(String root) {
        return new ApiIndex(
                root + DOCUMENT_PATH,
                endpoints.stream()
                        .map(endpoint -> new ApiIndex.Endpoint(root + endpoint.path(), endpoint.purpose()))
                        .toList());
    }
}
