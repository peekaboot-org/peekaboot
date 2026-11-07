package org.peekaboot.backend.domain.errors;

import java.util.List;

/** The error digest's entries the request selected, the one seen latest first. */
public record ErrorDigestResponse(List<ErrorDigestEntry> errors) {}
