package org.peekaboot.backend.domain.trace;

import java.util.Map;

public record HttpResponse(int status, Map<String, String> headers) {}
