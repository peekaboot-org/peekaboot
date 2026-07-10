package net.osslabz.peekaboot.backend.domain.trace;

import net.osslabz.peekaboot.backend.tracing.event.RequestCompletedEvent;

import java.util.List;
import java.util.Map;

public record HttpExchange(
    HttpRequest request,
    HttpResponse response
) {

    public static HttpExchange from(RequestCompletedEvent event) {
        HttpRequest.Body body = new HttpRequest.Body(
                event.requestBodyTruncated(),
                event.requestBody()
        );
        HttpRequest.Controller controller = new HttpRequest.Controller(
                event.controllerClass(),
                event.controllerMethod()
        );
        HttpRequest.Params params = new HttpRequest.Params(
                event.queryParams() != null ? event.queryParams() : Map.of(),
                event.formParams() != null ? event.formParams() : Map.of(),
                event.uploadedFiles() != null
                        ? event.uploadedFiles().stream()
                            .map(f -> new HttpRequest.UploadedFile(f.fieldName(), f.originalFilename(), f.contentType(), f.size()))
                            .toList()
                        : List.of()
        );
        HttpRequest request = new HttpRequest(
                event.method(),
                event.path(),
                event.queryString(),
                event.requestHeaders() != null ? event.requestHeaders() : Map.of(),
                body,
                controller,
                params
        );
        HttpResponse response = new HttpResponse(
                event.status(),
                event.responseHeaders() != null ? event.responseHeaders() : Map.of()
        );
        return new HttpExchange(request, response);
    }
}
