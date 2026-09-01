package org.peekaboot.backend.config;

import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

/**
 * Serialises Peekaboot's own response types with {@link PeekabootJson#MAPPER}, registered
 * ahead of the application's converters by {@link PeekabootWebConfig}. Spring MVC picks a
 * converter per return value, not per controller, so the scope is the value's package:
 * every Peekaboot controller returns a Peekaboot type and nothing else does. Read-only
 * requests only, so it never reads.
 */
public final class PeekabootJsonMessageConverter extends JacksonJsonHttpMessageConverter {

    private static final String PEEKABOOT_PACKAGE_PREFIX = "org.peekaboot.backend.";

    public PeekabootJsonMessageConverter() {
        super(PeekabootJson.MAPPER);
    }

    @Override
    public boolean canRead(ResolvableType type, MediaType mediaType) {
        return false;
    }

    @Override
    public boolean canWrite(ResolvableType type, Class<?> clazz, MediaType mediaType) {
        return clazz != null
                && clazz.getPackageName().startsWith(PEEKABOOT_PACKAGE_PREFIX)
                && super.canWrite(type, clazz, mediaType);
    }
}
