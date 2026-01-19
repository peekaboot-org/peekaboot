package net.osslabz.peekaboot.backend.domain.trace;

public record HttpExchange(
    HttpRequest request,
    HttpResponse response
) {}
