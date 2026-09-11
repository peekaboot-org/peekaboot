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
     * Detected-only: whether the launch context was a deployment, independent of any explicit
     * {@link #SECURITY_ENABLED} override. An application must never set this itself - it exists
     * so a component can tell a detected default from an override after {@link #SECURITY_ENABLED}
     * has already resolved, which reading that key alone cannot answer.
     */
    static final String SECURITY_DEPLOYMENT_DETECTED = "peekaboot.security.deployment-detected";

    /**
     * The property source carrying the launch-context defaults - present under this name only
     * when the application has not already called {@code SpringApplication.setDefaultProperties}
     * or {@code SpringApplicationBuilder.properties}; otherwise these entries are folded into
     * Boot's own {@code defaultProperties} source instead and this name never appears. A signal
     * that must survive regardless of which branch ran needs its own property key, not this
     * source's name - see {@link #SECURITY_DEPLOYMENT_DETECTED}.
     */
    static final String DETECTION_PROPERTY_SOURCE_NAME = "peekabootDetection";

    private PeekabootPropertyKeys() {}
}
