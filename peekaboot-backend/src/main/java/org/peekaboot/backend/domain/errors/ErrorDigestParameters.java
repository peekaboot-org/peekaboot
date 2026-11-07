package org.peekaboot.backend.domain.errors;

import org.jspecify.annotations.Nullable;

/**
 * The error digest's query parameters as the request sent them, each null where it is absent.
 * {@code ErrorDigestService} reads them, so a bad value is refused in one place.
 */
public record ErrorDigestParameters(
        @Nullable String since, @Nullable String route) {}
