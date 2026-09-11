package org.peekaboot.autoconfigure;

/** Property keys read outside a {@code @ConfigurationProperties} binding. */
final class PeekabootPropertyKeys {

    /** The master switch; has no fallback of its own, see PeekabootDefaultsEnvironmentPostProcessor. */
    static final String ENABLED = "peekaboot.enabled";

    /** The toolbar switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String DEV_TOOLBAR = "peekaboot.dev-toolbar";

    /** The persistence switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String STORAGE_ENABLED = "peekaboot.storage.enabled";

    /** The lifecycle-feature switch; on unless set to false. */
    static final String LIFECYCLE_ENABLED = "peekaboot.lifecycle.enabled";

    /** The tracing-feature switch; on unless set to false. */
    static final String TRACING_ENABLED = "peekaboot.tracing.enabled";

    /** The insights-feature switch; on unless set to false. */
    static final String INSIGHTS_ENABLED = "peekaboot.insights.enabled";

    /** The automatic-security switch; defaulted from the launch context like {@link #ENABLED}. */
    static final String SECURITY_ENABLED = "peekaboot.security.enabled";

    /**
     * The property source carrying the launch-context defaults. Readable by name so a
     * component can see what was detected even where the application overrode it.
     */
    static final String DETECTION_PROPERTY_SOURCE_NAME = "peekabootDetection";

    private PeekabootPropertyKeys() {}
}
